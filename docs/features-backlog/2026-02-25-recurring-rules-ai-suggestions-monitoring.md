# Recurring Rules - AI Suggestions & Metadata Monitoring

**Data utworzenia:** 2026-02-25
**Status:** Analiza - future features
**Autor:** Claude Code + User
**Powiązane dokumenty:**
- `2026-02-25-recurring-rules-technical-solutions.md` (rozwiązania techniczne)
- `2026-02-25-recurring-rules-edit-delete-alerts-design.md` (alerty)
- `2026-02-14-recurring-rule-engine-design.md` (funkcjonalny design)

---

## Spis treści

1. [AI Suggestions - Przyszłe sugestie reguł](#1-ai-suggestions---przyszłe-sugestie-reguł)
2. [Metadata Monitoring - Monitoring metadanych reguł](#2-metadata-monitoring---monitoring-metadanych-reguł)
3. [Przykłady reakcji na Error Scenarios](#3-przykłady-reakcji-na-error-scenarios)
4. [Dashboard & Alerting](#4-dashboard--alerting)
5. [Implementation Roadmap](#5-implementation-roadmap)

---

## 1. AI Suggestions - Przyszłe sugestie reguł

### 1.1 Koncepcja

AI analizuje historyczne transakcje użytkownika i sugeruje nowe reguły powtarzalne:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     AI-POWERED RULE SUGGESTIONS                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  DANE WEJŚCIOWE:                                                             │
│  ════════════════                                                            │
│  • Wszystkie CONFIRMED transakcje użytkownika (history)                     │
│  • Istniejące reguły (żeby nie duplikować)                                  │
│  • Kategorie i ich typy                                                      │
│  • Czas trwania CashFlow                                                     │
│                                                                              │
│  PATTERN DETECTION:                                                          │
│  ══════════════════                                                          │
│                                                                              │
│  1. TEMPORAL PATTERNS (powtarzalność w czasie)                              │
│     ───────────────────────────────────────────                             │
│     • Ta sama/podobna kwota pojawia się co miesiąc                          │
│     • Stały dzień miesiąca (±3 dni tolerancji)                              │
│     • Minimum 3 wystąpienia dla pewności                                    │
│                                                                              │
│     Przykład:                                                                │
│     - 2026-01-10: "Netflix" -29.99 PLN                                      │
│     - 2026-02-10: "Netflix subscription" -29.99 PLN                         │
│     - 2026-03-10: "NETFLIX" -29.99 PLN                                      │
│     → AI sugeruje: Monthly rule, 10th, 29.99 PLN, kategoria "Rozrywka"     │
│                                                                              │
│  2. COUNTERPARTY PATTERNS (ten sam kontrahent)                              │
│     ─────────────────────────────────────────────                           │
│     • Ta sama nazwa/konto bankowe                                           │
│     • Nawet jeśli kwoty różne                                               │
│                                                                              │
│     Przykład:                                                                │
│     - "TAURON ENERGIA" -145.32 PLN                                          │
│     - "TAURON ENERGIA" -167.89 PLN                                          │
│     - "TAURON ENERGIA" -132.45 PLN                                          │
│     → AI sugeruje: Monthly rule, ~150 PLN (szacunek), kategoria "Rachunki" │
│                                                                              │
│  3. CATEGORY PATTERNS                                                        │
│     ─────────────────                                                        │
│     • Regularne wpływy/wypływy w tej samej kategorii                        │
│                                                                              │
│     Przykład:                                                                │
│     - Kategoria "Wynagrodzenie" ma wpływy 5-tego każdego miesiąca          │
│     → AI sugeruje: Salary rule, 5th, 8500 PLN                              │
│                                                                              │
│  4. AMOUNT CLUSTERING                                                        │
│     ─────────────────                                                        │
│     • Transakcje z podobnymi kwotami grupowane razem                        │
│     • Wykrywanie "paczek" wydatków                                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Algorytm wykrywania

```java
package com.multi.vidulum.recurring_rules.ai;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringPatternDetector {

    private static final int MIN_OCCURRENCES = 3;
    private static final int DATE_TOLERANCE_DAYS = 5;
    private static final double AMOUNT_TOLERANCE_PERCENT = 0.05; // 5%

    /**
     * Analyzes transaction history and detects recurring patterns.
     */
    public List<RuleSuggestion> detectPatterns(
            CashFlowId cashFlowId,
            List<ConfirmedTransaction> transactions,
            List<RecurringRule> existingRules
    ) {
        List<RuleSuggestion> suggestions = new ArrayList<>();

        // 1. Group by potential counterparty (name similarity)
        Map<String, List<ConfirmedTransaction>> byCounterparty =
            groupByCounterparty(transactions);

        for (var entry : byCounterparty.entrySet()) {
            List<ConfirmedTransaction> group = entry.getValue();

            if (group.size() < MIN_OCCURRENCES) {
                continue;
            }

            // 2. Detect temporal pattern
            Optional<TemporalPattern> pattern = detectTemporalPattern(group);

            if (pattern.isEmpty()) {
                continue;
            }

            // 3. Check if similar rule exists
            if (similarRuleExists(existingRules, pattern.get())) {
                continue;
            }

            // 4. Calculate confidence
            double confidence = calculateConfidence(group, pattern.get());

            if (confidence < 0.7) { // 70% threshold
                continue;
            }

            // 5. Create suggestion
            suggestions.add(createSuggestion(
                entry.getKey(),
                group,
                pattern.get(),
                confidence
            ));
        }

        return suggestions;
    }

    private Optional<TemporalPattern> detectTemporalPattern(
            List<ConfirmedTransaction> transactions
    ) {
        // Sort by date
        List<ConfirmedTransaction> sorted = transactions.stream()
            .sorted(Comparator.comparing(ConfirmedTransaction::paidDate))
            .toList();

        // Calculate intervals between transactions
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            long days = ChronoUnit.DAYS.between(
                sorted.get(i-1).paidDate(),
                sorted.get(i).paidDate()
            );
            intervals.add(days);
        }

        // Detect pattern type
        double avgInterval = intervals.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0);

        double stdDev = calculateStdDev(intervals, avgInterval);

        // Monthly pattern: ~30 days ± tolerance
        if (avgInterval >= 25 && avgInterval <= 35 && stdDev <= DATE_TOLERANCE_DAYS) {
            int dayOfMonth = detectMostCommonDayOfMonth(sorted);
            return Optional.of(new TemporalPattern(
                PatternType.MONTHLY,
                dayOfMonth,
                1,
                avgInterval,
                stdDev
            ));
        }

        // Weekly pattern: ~7 days
        if (avgInterval >= 5 && avgInterval <= 9 && stdDev <= 2) {
            DayOfWeek dayOfWeek = detectMostCommonDayOfWeek(sorted);
            return Optional.of(new TemporalPattern(
                PatternType.WEEKLY,
                dayOfWeek.getValue(),
                1,
                avgInterval,
                stdDev
            ));
        }

        // Bi-weekly pattern: ~14 days
        if (avgInterval >= 12 && avgInterval <= 16 && stdDev <= 2) {
            DayOfWeek dayOfWeek = detectMostCommonDayOfWeek(sorted);
            return Optional.of(new TemporalPattern(
                PatternType.WEEKLY,
                dayOfWeek.getValue(),
                2,
                avgInterval,
                stdDev
            ));
        }

        // Quarterly pattern: ~90 days
        if (avgInterval >= 85 && avgInterval <= 95 && stdDev <= 10) {
            int dayOfMonth = detectMostCommonDayOfMonth(sorted);
            return Optional.of(new TemporalPattern(
                PatternType.MONTHLY,
                dayOfMonth,
                3, // every 3 months
                avgInterval,
                stdDev
            ));
        }

        // Yearly pattern: ~365 days
        if (avgInterval >= 355 && avgInterval <= 375) {
            int dayOfMonth = detectMostCommonDayOfMonth(sorted);
            int month = detectMostCommonMonth(sorted);
            return Optional.of(new TemporalPattern(
                PatternType.YEARLY,
                dayOfMonth,
                month,
                avgInterval,
                stdDev
            ));
        }

        return Optional.empty();
    }

    private double calculateConfidence(
            List<ConfirmedTransaction> transactions,
            TemporalPattern pattern
    ) {
        double score = 0.0;

        // 1. Number of occurrences (more = higher confidence)
        int count = transactions.size();
        score += Math.min(count / 12.0, 1.0) * 0.3; // Max 30% for 12+ occurrences

        // 2. Regularity (low std dev = higher confidence)
        double normalizedStdDev = pattern.stdDev() / pattern.avgInterval();
        score += (1 - Math.min(normalizedStdDev, 1.0)) * 0.3; // Max 30%

        // 3. Amount consistency
        List<BigDecimal> amounts = transactions.stream()
            .map(t -> t.amount().abs())
            .toList();
        BigDecimal avgAmount = calculateAverage(amounts);
        double amountVariance = calculateVariance(amounts, avgAmount);
        score += (1 - Math.min(amountVariance / avgAmount.doubleValue(), 1.0)) * 0.2; // Max 20%

        // 4. Recent activity (recent transactions = higher confidence)
        LocalDate lastTransaction = transactions.stream()
            .map(ConfirmedTransaction::paidDate)
            .max(LocalDate::compareTo)
            .orElse(LocalDate.MIN);
        long daysSinceLast = ChronoUnit.DAYS.between(lastTransaction, LocalDate.now());
        if (daysSinceLast <= pattern.avgInterval() * 1.5) {
            score += 0.2; // 20% for recent activity
        }

        return score;
    }

    private Map<String, List<ConfirmedTransaction>> groupByCounterparty(
            List<ConfirmedTransaction> transactions
    ) {
        // Use fuzzy matching for counterparty names
        // "Netflix", "NETFLIX", "Netflix subscription" → same group

        Map<String, List<ConfirmedTransaction>> groups = new HashMap<>();

        for (ConfirmedTransaction tx : transactions) {
            String normalizedName = normalizeCounterpartyName(tx.description());
            groups.computeIfAbsent(normalizedName, k -> new ArrayList<>()).add(tx);
        }

        return groups;
    }

    private String normalizeCounterpartyName(String name) {
        // Remove common suffixes, normalize case, etc.
        return name
            .toUpperCase()
            .replaceAll("[^A-Z0-9]", " ")
            .replaceAll("\\s+", " ")
            .trim()
            .split(" ")[0]; // Take first word as key
    }
}

// Supporting types
public record TemporalPattern(
    PatternType type,
    int dayValue,     // day of month (1-31) or day of week (1-7)
    int interval,     // 1 = every, 2 = every other, etc.
    double avgInterval,
    double stdDev
) {}

public enum PatternType {
    DAILY, WEEKLY, MONTHLY, YEARLY
}

public record RuleSuggestion(
    String suggestedName,
    String counterparty,
    Money suggestedAmount,
    boolean amountIsEstimate,
    Type type,               // INFLOW or OUTFLOW
    String suggestedCategory,
    RecurrencePattern pattern,
    double confidence,       // 0.0 - 1.0
    int basedOnTransactions, // How many transactions this is based on
    LocalDate firstOccurrence,
    LocalDate lastOccurrence,
    List<String> relatedTransactionIds
) {}
```

### 1.3 UI - Sugestie dla użytkownika

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     UI: AI Rule Suggestions                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Na stronie Recurring Rules:                                                 │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ 💡 AI detected 3 potential recurring patterns               [View]   │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  Po kliknięciu "View":                                                       │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ Suggested Recurring Rules                                       [×]  │  │
│  ├──────────────────────────────────────────────────────────────────────┤  │
│  │                                                                      │  │
│  │  Based on your transaction history, we detected these patterns:     │  │
│  │                                                                      │  │
│  │  ┌────────────────────────────────────────────────────────────────┐ │  │
│  │  │ 🎬 Netflix                                                      │ │  │
│  │  │    29.99 PLN monthly · 10th of each month                      │ │  │
│  │  │    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 95% confidence        │ │  │
│  │  │    Based on 6 transactions (Jan - Jun 2026)                    │ │  │
│  │  │                                                                 │ │  │
│  │  │    [Create Rule]  [View Transactions]  [Dismiss]               │ │  │
│  │  └────────────────────────────────────────────────────────────────┘ │  │
│  │                                                                      │  │
│  │  ┌────────────────────────────────────────────────────────────────┐ │  │
│  │  │ ⚡ TAURON Energia                                               │ │  │
│  │  │    ~150 PLN monthly (varies) · ~15th of each month             │ │  │
│  │  │    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 78% confidence              │ │  │
│  │  │    Based on 4 transactions                                     │ │  │
│  │  │    ⚠️ Amount varies: 132-168 PLN                               │ │  │
│  │  │                                                                 │ │  │
│  │  │    [Create Rule]  [View Transactions]  [Dismiss]               │ │  │
│  │  └────────────────────────────────────────────────────────────────┘ │  │
│  │                                                                      │  │
│  │  ┌────────────────────────────────────────────────────────────────┐ │  │
│  │  │ 💰 Salary                                                       │ │  │
│  │  │    8,500 PLN monthly · 5th of each month                       │ │  │
│  │  │    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 92% confidence        │ │  │
│  │  │    Based on 5 transactions                                     │ │  │
│  │  │                                                                 │ │  │
│  │  │    [Create Rule]  [View Transactions]  [Dismiss]               │ │  │
│  │  └────────────────────────────────────────────────────────────────┘ │  │
│  │                                                                      │  │
│  │  ─────────────────────────────────────────────────────────────────  │  │
│  │                                                                      │  │
│  │  [Dismiss All]                              [Create All 3 Rules]    │  │
│  │                                                                      │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.4 Kiedy uruchamiać AI detection?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     AI DETECTION TRIGGERS                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. ON DEMAND (user clicks "Find patterns")                                 │
│     → Natychmiastowe skanowanie                                              │
│     → Pokazuje wyniki w modal                                                │
│                                                                              │
│  2. SCHEDULED (background job)                                               │
│     → Codziennie o 3:00                                                      │
│     → Dla CashFlows z min. 3 miesiącami historii                            │
│     → Zapisuje sugestie do DB                                                │
│     → Pokazuje badge "3 suggestions" w UI                                    │
│                                                                              │
│  3. AFTER CSV IMPORT                                                         │
│     → Po zaimportowaniu historii transakcji                                  │
│     → Automatycznie skanuje nowe dane                                        │
│     → Sugeruje reguły pasujące do importu                                    │
│                                                                              │
│  4. AFTER MONTH ROLLOVER (optional)                                          │
│     → Sprawdza czy pojawiły się nowe wzorce                                  │
│     → Wysyła powiadomienie jeśli coś nowego                                  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.5 Dane potrzebne do ML (przyszłość)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     ML TRAINING DATA (Future)                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Żeby w przyszłości trenować model ML, zbieraj:                             │
│                                                                              │
│  1. SUGGESTION FEEDBACK                                                      │
│     • User accepted suggestion → positive example                            │
│     • User dismissed suggestion → negative example                           │
│     • User created rule manually → missed opportunity                        │
│                                                                              │
│  2. RULE PERFORMANCE                                                         │
│     • Rule matched confirmed transaction → good pattern                      │
│     • Rule generated but never confirmed → bad pattern                       │
│     • Rule edited after creation → incomplete detection                      │
│                                                                              │
│  3. USER BEHAVIOR                                                            │
│     • Which suggestions are accepted most?                                   │
│     • What confidence threshold works?                                       │
│     • Which pattern types are most useful?                                   │
│                                                                              │
│  STORAGE:                                                                    │
│  ```javascript                                                               │
│  // ai_suggestions collection                                                │
│  {                                                                           │
│    "_id": "SUG10000001",                                                    │
│    "cashFlowId": "CF10000001",                                              │
│    "userId": "U10000001",                                                   │
│    "suggestedAt": ISODate("2026-02-25"),                                    │
│    "suggestion": {                                                           │
│      "name": "Netflix",                                                      │
│      "amount": 29.99,                                                        │
│      "pattern": "MONTHLY_10TH",                                             │
│      "confidence": 0.95                                                      │
│    },                                                                        │
│    "basedOnTransactions": ["TX001", "TX002", "TX003"],                      │
│    "outcome": "ACCEPTED",  // ACCEPTED, DISMISSED, EXPIRED                   │
│    "outcomeAt": ISODate("2026-02-26"),                                      │
│    "createdRuleId": "RR10000005"  // if accepted                            │
│  }                                                                           │
│  ```                                                                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Metadata Monitoring - Monitoring metadanych reguł

### 2.1 Dlaczego monitoring metadanych?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     WHY RULE METADATA MONITORING?                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PROBLEM:                                                                    │
│  Nie każda reguła jest aktywna każdego dnia. Reguły mogą:                   │
│  • Generować transakcje raz na miesiąc                                       │
│  • Być aktywne tylko w określonych miesiącach                               │
│  • Mieć różny track record (skuteczność matchowania)                        │
│  • Mieć historię błędów                                                      │
│                                                                              │
│  BEZ MONITORINGU:                                                            │
│  • Nie wiadomo kiedy reguła "odpali"                                        │
│  • Nie wiadomo ile transakcji wygenerowała                                  │
│  • Nie wiadomo ile z nich zostało opłaconych                                │
│  • Nie wiadomo jakie były problemy                                          │
│                                                                              │
│  Z MONITORINGIEM:                                                            │
│  • Dashboard: "5 rules will fire this week"                                 │
│  • Stats: "Rule X: 12 generated, 10 confirmed (83% match rate)"             │
│  • Alerts: "Rule Y failed 3 times this month"                               │
│  • Predictions: "Expected cash flow from rules: -15,000 PLN"                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Metadane do śledzenia

```java
package com.multi.vidulum.recurring_rules.domain;

@Getter
@AllArgsConstructor
public class RecurringRule {
    // ... existing fields

    // ════════════════════════════════════════════════════════════════════════
    // GENERATION METADATA
    // ════════════════════════════════════════════════════════════════════════

    private GenerationStatus generationStatus;      // IDLE, PENDING, IN_PROGRESS, COMPLETED, FAILED
    private String lastGenerationError;             // Error message if failed
    private ZonedDateTime lastGenerationAttempt;    // When last generation was attempted
    private int consecutiveFailures;                // How many failures in a row

    // ════════════════════════════════════════════════════════════════════════
    // STATISTICS METADATA (NEW)
    // ════════════════════════════════════════════════════════════════════════

    private RuleStatistics statistics;

    @Getter
    @AllArgsConstructor
    public static class RuleStatistics {

        // Generation stats
        private int totalGenerated;           // Total transactions ever generated
        private int generatedThisMonth;       // Generated in current month
        private YearMonth lastGeneratedPeriod; // Last period generated

        // Confirmation stats (matching)
        private int totalConfirmed;           // How many generated were confirmed
        private int confirmedThisMonth;       // Confirmed this month
        private LocalDate lastConfirmedDate;  // When last confirmation happened

        // Match rate
        public double getMatchRate() {
            if (totalGenerated == 0) return 0.0;
            return (double) totalConfirmed / totalGenerated;
        }

        // Pending stats
        private int pendingCount;             // EXPECTED but not yet confirmed
        private Money pendingAmount;          // Total amount pending

        // Error history
        private int totalFailures;            // All-time failure count
        private int failuresThisMonth;        // Failures this month
        private List<FailureRecord> recentFailures;  // Last 10 failures

        // Timing predictions
        private LocalDate nextExpectedExecution;     // When will next generation happen
        private Money nextExpectedAmount;            // What amount will be generated
    }

    @Getter
    @AllArgsConstructor
    public static class FailureRecord {
        private ZonedDateTime occurredAt;
        private String errorType;         // CATEGORY_ARCHIVED, HTTP_ERROR, etc.
        private String errorMessage;
        private boolean wasRecovered;
        private ZonedDateTime recoveredAt;
    }

    // ════════════════════════════════════════════════════════════════════════
    // SCHEDULING METADATA (NEW)
    // ════════════════════════════════════════════════════════════════════════

    private ScheduleMetadata scheduleMetadata;

    @Getter
    @AllArgsConstructor
    public static class ScheduleMetadata {
        private LocalDate nextScheduledDate;      // Next date this rule will generate
        private int remainingOccurrences;         // How many more times (if maxOccurrences set)
        private LocalDate scheduledEndDate;       // When rule will naturally end
        private boolean activeInCurrentMonth;     // Is this month in activeMonths?
        private boolean activeInNextMonth;        // Is next month in activeMonths?
    }
}
```

### 2.3 MongoDB Schema dla metadanych

```javascript
// recurring_rules collection - extended schema

{
  "_id": "RR10000001",
  // ... existing fields ...

  // ═══════════════════════════════════════════════════════════════════════
  // STATISTICS (embedded document)
  // ═══════════════════════════════════════════════════════════════════════
  "statistics": {
    "generation": {
      "totalGenerated": 48,
      "generatedThisMonth": 4,
      "lastGeneratedPeriod": "2026-02"
    },
    "confirmation": {
      "totalConfirmed": 42,
      "confirmedThisMonth": 3,
      "lastConfirmedDate": ISODate("2026-02-15"),
      "matchRate": 0.875  // 42/48
    },
    "pending": {
      "count": 6,
      "totalAmount": { "amount": 12000.00, "currency": "PLN" }
    },
    "errors": {
      "totalFailures": 3,
      "failuresThisMonth": 0,
      "recentFailures": [
        {
          "occurredAt": ISODate("2026-01-15T10:00:00Z"),
          "errorType": "HTTP_TIMEOUT",
          "errorMessage": "Connection timed out",
          "wasRecovered": true,
          "recoveredAt": ISODate("2026-01-15T10:15:00Z")
        }
      ]
    }
  },

  // ═══════════════════════════════════════════════════════════════════════
  // SCHEDULE METADATA (embedded document)
  // ═══════════════════════════════════════════════════════════════════════
  "scheduleMetadata": {
    "nextScheduledDate": ISODate("2026-03-10"),
    "remainingOccurrences": null,  // null = infinite
    "scheduledEndDate": null,      // null = no end
    "activeInCurrentMonth": true,
    "activeInNextMonth": true,
    "estimatedMonthlyImpact": { "amount": 2000.00, "currency": "PLN" }
  },

  // ═══════════════════════════════════════════════════════════════════════
  // EXECUTION HISTORY (separate collection for large data)
  // ═══════════════════════════════════════════════════════════════════════
  // Przechowywane w osobnej kolekcji: recurring_rule_executions
}

// Indexes for monitoring queries
db.recurring_rules.createIndex({ "scheduleMetadata.nextScheduledDate": 1 })
db.recurring_rules.createIndex({ "statistics.errors.failuresThisMonth": -1 })
db.recurring_rules.createIndex({ "statistics.confirmation.matchRate": 1 })
db.recurring_rules.createIndex({ "generationStatus": 1 })
```

### 2.4 Execution History Collection

```javascript
// recurring_rule_executions - detailed history

{
  "_id": "EXEC10000001",
  "ruleId": "RR10000001",
  "cashFlowId": "CF10000001",
  "userId": "U10000001",

  // Execution details
  "executionType": "GENERATION",  // GENERATION, RETRY, RECOVERY
  "executedAt": ISODate("2026-02-01T00:05:00Z"),
  "triggerSource": "MONTH_ROLLOVER",  // MONTH_ROLLOVER, MANUAL, RECOVERY_JOB

  // Results
  "status": "SUCCESS",  // SUCCESS, PARTIAL, FAILED
  "generatedTransactions": [
    {
      "cashChangeId": "CC10000001",
      "dueDate": ISODate("2026-03-10"),
      "amount": { "amount": 2000.00, "currency": "PLN" }
    }
  ],
  "transactionCount": 1,

  // Timing
  "duration": 234,  // milliseconds

  // Error info (if failed)
  "error": null,
  "errorCode": null,
  "retryCount": 0,

  // HTTP call details (for debugging)
  "httpDetails": {
    "endpoint": "POST /expected-cash-changes/batch",
    "requestSize": 512,
    "responseCode": 201,
    "responseTime": 189
  }
}

// Indexes
db.recurring_rule_executions.createIndex({ "ruleId": 1, "executedAt": -1 })
db.recurring_rule_executions.createIndex({ "status": 1, "executedAt": -1 })
db.recurring_rule_executions.createIndex({ "cashFlowId": 1, "executedAt": -1 })

// TTL index - keep only last 6 months
db.recurring_rule_executions.createIndex(
  { "executedAt": 1 },
  { expireAfterSeconds: 15552000 }  // 180 days
)
```

### 2.5 Monitoring Service

```java
package com.multi.vidulum.recurring_rules.app;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuleMonitoringService {

    private final RecurringRuleRepository ruleRepository;
    private final RuleExecutionRepository executionRepository;
    private final MeterRegistry meterRegistry;

    // ════════════════════════════════════════════════════════════════════════
    // SCHEDULED JOBS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Update schedule metadata for all rules.
     * Runs daily at 1 AM.
     */
    @Scheduled(cron = "0 0 1 * * *")
    public void updateScheduleMetadata() {
        log.info("Starting schedule metadata update");

        List<RecurringRule> activeRules = ruleRepository
            .findByStatus(RuleStatus.ACTIVE);

        for (RecurringRule rule : activeRules) {
            ScheduleMetadata metadata = calculateScheduleMetadata(rule);
            rule.setScheduleMetadata(metadata);
            ruleRepository.save(rule);
        }

        log.info("Updated schedule metadata for {} rules", activeRules.size());
    }

    /**
     * Calculate monthly statistics.
     * Runs on 1st of each month at 2 AM.
     */
    @Scheduled(cron = "0 0 2 1 * *")
    public void calculateMonthlyStatistics() {
        log.info("Starting monthly statistics calculation");

        YearMonth lastMonth = YearMonth.now().minusMonths(1);

        List<RecurringRule> allRules = ruleRepository.findAll();

        for (RecurringRule rule : allRules) {
            RuleStatistics stats = calculateStatistics(rule, lastMonth);
            rule.setStatistics(stats);
            ruleRepository.save(rule);
        }

        log.info("Calculated statistics for {} rules", allRules.size());
    }

    // ════════════════════════════════════════════════════════════════════════
    // QUERY METHODS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Get rules scheduled to fire within date range.
     */
    public List<ScheduledRuleInfo> getRulesScheduledBetween(
            CashFlowId cashFlowId,
            LocalDate start,
            LocalDate end
    ) {
        return ruleRepository
            .findByCashFlowIdAndNextScheduledDateBetween(cashFlowId, start, end)
            .stream()
            .map(this::toScheduledRuleInfo)
            .toList();
    }

    /**
     * Get rules with low match rate (potential problems).
     */
    public List<RecurringRule> getRulesWithLowMatchRate(
            CashFlowId cashFlowId,
            double threshold
    ) {
        return ruleRepository
            .findByCashFlowIdAndMatchRateLessThan(cashFlowId, threshold);
    }

    /**
     * Get rules with recent failures.
     */
    public List<RecurringRule> getRulesWithRecentFailures(
            CashFlowId cashFlowId,
            int minFailures
    ) {
        return ruleRepository
            .findByCashFlowIdAndFailuresThisMonthGreaterThan(cashFlowId, minFailures);
    }

    /**
     * Get execution history for a rule.
     */
    public List<RuleExecution> getExecutionHistory(
            RecurringRuleId ruleId,
            int limit
    ) {
        return executionRepository
            .findByRuleIdOrderByExecutedAtDesc(ruleId, PageRequest.of(0, limit));
    }

    /**
     * Get dashboard summary for CashFlow.
     */
    public RulesDashboardSummary getDashboardSummary(CashFlowId cashFlowId) {
        List<RecurringRule> rules = ruleRepository.findByCashFlowId(cashFlowId);

        int activeCount = (int) rules.stream()
            .filter(r -> r.getStatus() == RuleStatus.ACTIVE)
            .count();

        int pausedCount = (int) rules.stream()
            .filter(r -> r.getStatus() == RuleStatus.PAUSED)
            .count();

        int failedCount = (int) rules.stream()
            .filter(r -> r.getGenerationStatus() == GenerationStatus.FAILED)
            .count();

        List<RecurringRule> scheduledThisWeek = rules.stream()
            .filter(r -> r.getStatus() == RuleStatus.ACTIVE)
            .filter(r -> isScheduledThisWeek(r))
            .toList();

        Money expectedOutflow = calculateExpectedOutflow(rules, YearMonth.now());
        Money expectedInflow = calculateExpectedInflow(rules, YearMonth.now());

        double avgMatchRate = rules.stream()
            .filter(r -> r.getStatistics() != null)
            .mapToDouble(r -> r.getStatistics().getMatchRate())
            .average()
            .orElse(0.0);

        return new RulesDashboardSummary(
            activeCount,
            pausedCount,
            failedCount,
            scheduledThisWeek.size(),
            expectedInflow,
            expectedOutflow,
            avgMatchRate,
            rules.stream()
                .filter(r -> r.getStatistics() != null &&
                            r.getStatistics().getMatchRate() < 0.5)
                .map(r -> r.getName().value())
                .toList()
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════════════════

    private ScheduleMetadata calculateScheduleMetadata(RecurringRule rule) {
        LocalDate today = LocalDate.now();

        // Calculate next scheduled date
        LocalDate nextDate = rule.getPattern()
            .getNextOccurrence(today, rule.getExcludedDates());

        // Check if in active months
        boolean activeCurrentMonth = rule.isActiveInMonth(YearMonth.now());
        boolean activeNextMonth = rule.isActiveInMonth(YearMonth.now().plusMonths(1));

        // Calculate remaining occurrences
        Integer remaining = null;
        if (rule.getMaxOccurrences() != null) {
            remaining = rule.getMaxOccurrences() - rule.getStatistics().getTotalGenerated();
        }

        // Calculate end date
        LocalDate endDate = rule.getEndDate();
        if (endDate == null && remaining != null && remaining <= 12) {
            // Estimate end date based on pattern
            endDate = estimateEndDate(rule, remaining);
        }

        return new ScheduleMetadata(
            nextDate,
            remaining,
            endDate,
            activeCurrentMonth,
            activeNextMonth,
            rule.getAmount()
        );
    }
}

// Dashboard summary
public record RulesDashboardSummary(
    int activeRules,
    int pausedRules,
    int failedRules,
    int scheduledThisWeek,
    Money expectedInflow,
    Money expectedOutflow,
    double averageMatchRate,
    List<String> lowPerformingRules
) {}

public record ScheduledRuleInfo(
    RecurringRuleId ruleId,
    String ruleName,
    LocalDate scheduledDate,
    Money amount,
    Type type,
    String categoryName
) {}
```

---

## 3. Przykłady reakcji na Error Scenarios

### 3.1 Dashboard z alertami

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     MONITORING DASHBOARD                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Recurring Rules Overview                                    Feb 2026   │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐              │ │
│  │  │    12    │  │    2     │  │    1     │  │    5     │              │ │
│  │  │  Active  │  │  Paused  │  │  Failed  │  │ This Week│              │ │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘              │ │
│  │       ✓             ⏸            ⚠️            📅                     │ │
│  │                                                                        │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  Expected Cash Flow from Rules (March 2026):                          │ │
│  │                                                                        │ │
│  │  Inflows:    +8,500 PLN   ████████████████████████░░░░░░              │ │
│  │  Outflows:  -12,350 PLN   ████████████████████████████████            │ │
│  │  ───────────────────────                                               │ │
│  │  Net:        -3,850 PLN                                                │ │
│  │                                                                        │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  ⚠️ Issues requiring attention:                                        │ │
│  │                                                                        │ │
│  │  • "Czynsz" - Generation failed (category archived)      [Fix]       │ │
│  │  • "Netflix" - Low match rate (42%)                       [Review]    │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Przykładowe scenariusze i reakcje

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     ERROR SCENARIO RESPONSES                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO 1: Rule fails 3 times in a row                                    │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Detection:                                                                  │
│  rule.consecutiveFailures >= 3                                              │
│                                                                              │
│  Monitoring Alert:                                                           │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │ ⚠️ ALERT: Rule "Czynsz" failed 3 consecutive times          12:34  │    │
│  │                                                                      │    │
│  │ Last error: HTTP 503 Service Unavailable                            │    │
│  │ Attempts: 12:30, 12:31, 12:34                                       │    │
│  │                                                                      │    │
│  │ Next auto-retry: 12:42 (8 min backoff)                              │    │
│  │ Auto-pause after: 2 more failures                                   │    │
│  │                                                                      │    │
│  │ [View Details]  [Retry Now]  [Pause Rule]                           │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  System Action:                                                              │
│  - Increment metrics: recurring_rules_failures_total                        │
│  - Log: ERROR "Rule RR10000001 failed: HTTP 503"                           │
│  - Send notification to user (if enabled)                                   │
│  - Schedule retry with exponential backoff                                  │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  SCENARIO 2: Rule has low match rate (<50%)                                 │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Detection:                                                                  │
│  rule.statistics.matchRate < 0.5 && rule.statistics.totalGenerated >= 5   │
│                                                                              │
│  Weekly Report Alert:                                                        │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │ ℹ️ Low Match Rate Warning                                           │    │
│  │                                                                      │    │
│  │ Rule "Netflix" has a 42% match rate:                                │    │
│  │ • Generated: 12 transactions                                        │    │
│  │ • Confirmed: 5 transactions                                         │    │
│  │ • Still pending: 7 transactions                                     │    │
│  │                                                                      │    │
│  │ Possible causes:                                                     │    │
│  │ • Wrong amount (expected: 29.99 PLN)                                │    │
│  │ • Wrong day (expected: 10th)                                        │    │
│  │ • Subscription cancelled?                                           │    │
│  │                                                                      │    │
│  │ [Review Rule]  [View Pending]  [Dismiss]                            │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  System Action:                                                              │
│  - Add to weekly digest email                                               │
│  - Show badge in UI: "1 rule needs attention"                              │
│  - Do NOT auto-pause (user decision)                                        │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  SCENARIO 3: Category archived while rule active                            │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Detection:                                                                  │
│  CategoryArchivedEvent received && rule uses this category                 │
│                                                                              │
│  Immediate Alert:                                                            │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │ ⚠️ Rules Auto-Paused                                                │    │
│  │                                                                      │    │
│  │ Category "Mieszkanie" was archived.                                 │    │
│  │                                                                      │    │
│  │ 2 rules were automatically paused:                                  │    │
│  │ • Czynsz (2,000 PLN monthly)                                       │    │
│  │ • Media (350 PLN monthly)                                          │    │
│  │                                                                      │    │
│  │ No new transactions will be generated until you:                    │    │
│  │ • Unarchive the category, or                                        │    │
│  │ • Change the rules to use a different category                     │    │
│  │                                                                      │    │
│  │ [View Rules]  [Unarchive Category]                                  │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  System Action:                                                              │
│  - Auto-pause affected rules with reason: CATEGORY_ARCHIVED                 │
│  - Send immediate notification                                               │
│  - Update rule.pauseReason                                                   │
│  - Log: WARN "Auto-paused 2 rules due to category archived"               │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  SCENARIO 4: CashFlow API consistently slow                                 │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Detection:                                                                  │
│  p95 response time > 5s for last 10 minutes                                │
│                                                                              │
│  System Alert (internal):                                                    │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │ 🔴 SYSTEM: CashFlow API Degraded Performance            OPS TEAM   │    │
│  │                                                                      │    │
│  │ recurring_rules → CashFlow API                                      │    │
│  │                                                                      │    │
│  │ p95 latency: 6.2s (threshold: 5s)                                   │    │
│  │ Error rate: 12% (threshold: 5%)                                     │    │
│  │ Affected rules: 15 pending generation                               │    │
│  │                                                                      │    │
│  │ Circuit breaker: OPEN                                                │    │
│  │ Will retry in: 30 seconds                                           │    │
│  │                                                                      │    │
│  │ [View Grafana]  [View Logs]                                         │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  System Action:                                                              │
│  - Circuit breaker opens (stop sending requests)                            │
│  - Queue pending generations                                                 │
│  - Alert ops team via PagerDuty/Slack                                       │
│  - Resume automatically when API recovers                                   │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  SCENARIO 5: Rule ending soon                                               │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Detection:                                                                  │
│  rule.scheduleMetadata.remainingOccurrences <= 2 OR                        │
│  rule.endDate within 30 days                                                │
│                                                                              │
│  Informational Alert:                                                        │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │ ℹ️ Rule Ending Soon                                                 │    │
│  │                                                                      │    │
│  │ "Gym Membership" will end after 2 more transactions.               │    │
│  │                                                                      │    │
│  │ • Next: March 1, 2026                                               │    │
│  │ • Final: April 1, 2026                                              │    │
│  │                                                                      │    │
│  │ After April, no more transactions will be generated.               │    │
│  │                                                                      │    │
│  │ [Extend Rule]  [OK, Got It]                                         │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.3 Prometheus Metrics dla monitoringu

```java
@Component
@RequiredArgsConstructor
public class RuleMonitoringMetrics {

    private final MeterRegistry registry;

    @PostConstruct
    void registerMetrics() {
        // ═══════════════════════════════════════════════════════════════════
        // COUNTERS
        // ═══════════════════════════════════════════════════════════════════

        // Generation attempts
        Counter.builder("recurring_rules_generation_attempts")
            .description("Number of generation attempts")
            .tag("status", "success")
            .register(registry);

        Counter.builder("recurring_rules_generation_attempts")
            .tag("status", "failure")
            .register(registry);

        // Failures by type
        Counter.builder("recurring_rules_failures_by_type")
            .description("Failures by error type")
            .tag("type", "HTTP_TIMEOUT")
            .register(registry);

        Counter.builder("recurring_rules_failures_by_type")
            .tag("type", "CATEGORY_ARCHIVED")
            .register(registry);

        Counter.builder("recurring_rules_failures_by_type")
            .tag("type", "CASHFLOW_NOT_FOUND")
            .register(registry);

        // Auto-pause events
        Counter.builder("recurring_rules_auto_paused")
            .description("Rules auto-paused by system")
            .tag("reason", "GENERATION_FAILED")
            .register(registry);

        Counter.builder("recurring_rules_auto_paused")
            .tag("reason", "CATEGORY_ARCHIVED")
            .register(registry);

        // ═══════════════════════════════════════════════════════════════════
        // GAUGES
        // ═══════════════════════════════════════════════════════════════════

        // Rules by status
        Gauge.builder("recurring_rules_count", ruleRepository,
            r -> r.countByStatus(RuleStatus.ACTIVE))
            .tag("status", "active")
            .register(registry);

        Gauge.builder("recurring_rules_count", ruleRepository,
            r -> r.countByStatus(RuleStatus.PAUSED))
            .tag("status", "paused")
            .register(registry);

        Gauge.builder("recurring_rules_count", ruleRepository,
            r -> r.countByGenerationStatus(GenerationStatus.FAILED))
            .tag("status", "failed")
            .register(registry);

        // Pending transactions
        Gauge.builder("recurring_rules_pending_transactions",
            ruleRepository, this::countPendingTransactions)
            .description("Number of expected but not confirmed transactions")
            .register(registry);

        // Rules with low match rate
        Gauge.builder("recurring_rules_low_match_rate",
            ruleRepository, r -> r.countByMatchRateLessThan(0.5))
            .description("Rules with match rate below 50%")
            .register(registry);

        // ═══════════════════════════════════════════════════════════════════
        // TIMERS
        // ═══════════════════════════════════════════════════════════════════

        // Generation duration
        Timer.builder("recurring_rules_generation_duration")
            .description("Time to generate transactions")
            .register(registry);

        // HTTP call duration
        Timer.builder("recurring_rules_http_duration")
            .description("HTTP call duration to CashFlow API")
            .tag("endpoint", "batch_create")
            .register(registry);

        // ═══════════════════════════════════════════════════════════════════
        // DISTRIBUTION SUMMARIES
        // ═══════════════════════════════════════════════════════════════════

        // Match rate distribution
        DistributionSummary.builder("recurring_rules_match_rate")
            .description("Distribution of rule match rates")
            .register(registry);

        // Transactions per generation
        DistributionSummary.builder("recurring_rules_transactions_per_generation")
            .description("Number of transactions generated per execution")
            .register(registry);
    }
}
```

### 3.4 Grafana Dashboard JSON (excerpt)

```json
{
  "dashboard": {
    "title": "Recurring Rules Monitoring",
    "panels": [
      {
        "title": "Rules by Status",
        "type": "piechart",
        "targets": [
          {
            "expr": "recurring_rules_count",
            "legendFormat": "{{status}}"
          }
        ]
      },
      {
        "title": "Generation Success Rate",
        "type": "stat",
        "targets": [
          {
            "expr": "rate(recurring_rules_generation_attempts{status=\"success\"}[1h]) / rate(recurring_rules_generation_attempts[1h]) * 100"
          }
        ],
        "thresholds": {
          "mode": "absolute",
          "steps": [
            { "color": "red", "value": 0 },
            { "color": "yellow", "value": 90 },
            { "color": "green", "value": 98 }
          ]
        }
      },
      {
        "title": "Failures Over Time",
        "type": "timeseries",
        "targets": [
          {
            "expr": "rate(recurring_rules_failures_by_type[5m])",
            "legendFormat": "{{type}}"
          }
        ]
      },
      {
        "title": "HTTP Latency (p95)",
        "type": "timeseries",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, rate(recurring_rules_http_duration_bucket[5m]))"
          }
        ]
      },
      {
        "title": "Low Match Rate Rules",
        "type": "table",
        "targets": [
          {
            "expr": "recurring_rules_match_rate < 0.5",
            "format": "table"
          }
        ]
      }
    ],
    "alerts": [
      {
        "name": "High Failure Rate",
        "condition": "rate(recurring_rules_generation_attempts{status=\"failure\"}[5m]) > 0.1",
        "severity": "warning"
      },
      {
        "name": "Many Failed Rules",
        "condition": "recurring_rules_count{status=\"failed\"} > 5",
        "severity": "critical"
      },
      {
        "name": "CashFlow API Slow",
        "condition": "histogram_quantile(0.95, rate(recurring_rules_http_duration_bucket[5m])) > 5",
        "severity": "warning"
      }
    ]
  }
}
```

---

## 4. Dashboard & Alerting

### 4.1 UI - Rule Details with Monitoring

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     UI: Rule Details with Stats                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Czynsz                                                     [Edit] [⋮]  │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │  Status: ● Active                 Category: Mieszkanie                │ │
│  │  Amount: 2,000 PLN               Type: OUTFLOW                        │ │
│  │  Pattern: Monthly, 10th          Started: Jan 2025                    │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  📊 Statistics                                                         │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │ │
│  │  │     48      │  │     42      │  │    87.5%    │  │      6      │  │ │
│  │  │  Generated  │  │  Confirmed  │  │ Match Rate  │  │   Pending   │  │ │
│  │  │   (total)   │  │   (total)   │  │             │  │             │  │ │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘  │ │
│  │                                                                        │ │
│  │  This Month (Feb 2026):                                               │ │
│  │  • Generated: 1    • Confirmed: 1    • Pending: 0                    │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  📅 Schedule                                                           │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  Next generation: March 10, 2026 (in 13 days)                        │ │
│  │  Active in March: ✓ Yes                                               │ │
│  │  Remaining: ∞ (no limit)                                              │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  🔧 Recent Activity                                                    │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  Feb 1, 00:05  ✓ Generated 1 transaction for March                   │ │
│  │  Jan 1, 00:05  ✓ Generated 1 transaction for February                │ │
│  │  Dec 1, 00:05  ✓ Generated 1 transaction for January                 │ │
│  │  Nov 15, 10:30 ⚠️ Generation failed (HTTP timeout)                    │ │
│  │                └─ Recovered Nov 15, 10:45                             │ │
│  │                                                                        │ │
│  │  [View All Activity]                                                   │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 UI - Scheduled Rules Calendar

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     UI: Scheduled Rules Calendar                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ March 2026                                              [< Prev] [Next >]│
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │   Mon    Tue    Wed    Thu    Fri    Sat    Sun                       │ │
│  │  ─────────────────────────────────────────────────                    │ │
│  │                                                                        │ │
│  │                                   1                                    │ │
│  │                               ┌─────────┐                              │ │
│  │                               │🔴 Gym   │                              │ │
│  │                               │   150 ↓ │                              │ │
│  │                               └─────────┘                              │ │
│  │                                                                        │ │
│  │   2      3      4      5      6      7      8                         │ │
│  │                        ┌─────────┐                                     │ │
│  │                        │🟢 Salary│                                     │ │
│  │                        │ 8,500 ↑ │                                     │ │
│  │                        └─────────┘                                     │ │
│  │                                                                        │ │
│  │   9     10     11     12     13     14     15                         │ │
│  │        ┌─────────┐                      ┌─────────┐                   │ │
│  │        │🟡 Czynsz│                      │🟡 Prąd  │                   │ │
│  │        │ 2,000 ↓ │                      │ ~150 ↓  │                   │ │
│  │        └─────────┘                      └─────────┘                   │ │
│  │        ┌─────────┐                                                     │ │
│  │        │🟡Netflix│                                                     │ │
│  │        │  29.99↓ │                                                     │ │
│  │        └─────────┘                                                     │ │
│  │                                                                        │ │
│  │  Legend: 🟢 Inflow   🟡 Outflow   🔴 Outflow (paused/failed)          │ │
│  │          ↑ Income    ↓ Expense    ~ Estimate                          │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  Summary for March 2026:                                              │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │ │
│  │  │ Expected Inflows:   +8,500 PLN                                  │  │ │
│  │  │ Expected Outflows: -2,479.99 PLN                                │  │ │
│  │  │ ─────────────────────────────────                               │  │ │
│  │  │ Net from Rules:    +6,020.01 PLN                                │  │ │
│  │  └─────────────────────────────────────────────────────────────────┘  │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Implementation Roadmap

### 5.1 Phases

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     IMPLEMENTATION ROADMAP                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PHASE 1: Basic Monitoring (Week 1-2)                                       │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ Add statistics fields to RecurringRule entity                            │
│  □ Track totalGenerated, totalConfirmed, lastConfirmedDate                  │
│  □ Calculate and store matchRate                                             │
│  □ Add basic Prometheus metrics (counters, gauges)                          │
│  □ Create simple dashboard in UI (stats on rule detail page)               │
│                                                                              │
│  PHASE 2: Schedule Metadata (Week 2-3)                                       │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ Add scheduleMetadata to RecurringRule                                    │
│  □ Implement calculateScheduleMetadata service                              │
│  □ Daily job to update next scheduled dates                                 │
│  □ UI: "Scheduled this week" section                                        │
│  □ UI: Calendar view (basic)                                                 │
│                                                                              │
│  PHASE 3: Execution History (Week 3-4)                                       │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ Create recurring_rule_executions collection                               │
│  □ Log every generation attempt                                              │
│  □ Store HTTP call details for debugging                                    │
│  □ UI: "Recent Activity" on rule detail page                                │
│  □ TTL cleanup for old records                                               │
│                                                                              │
│  PHASE 4: Alerting (Week 4-5)                                                │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ Low match rate detection                                                  │
│  □ Consecutive failure alerts                                                │
│  □ Rules ending soon notifications                                           │
│  □ Weekly digest email (summary of rule health)                             │
│  □ System alerts (ops team) for API issues                                  │
│                                                                              │
│  PHASE 5: AI Suggestions (Week 6-8) - FUTURE                                │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ Implement RecurringPatternDetector                                        │
│  □ Pattern detection algorithms (temporal, counterparty)                    │
│  □ Confidence scoring                                                        │
│  □ UI: Suggestions modal                                                     │
│  □ Feedback tracking (accepted/dismissed)                                   │
│  □ Background job for scheduled detection                                   │
│                                                                              │
│  PHASE 6: ML Enhancement (Future)                                            │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ Collect suggestion feedback for training                                  │
│  □ Build ML model for better pattern detection                              │
│  □ Personalized suggestions based on user behavior                          │
│  □ Anomaly detection in rule performance                                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Dependencies

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     DEPENDENCIES                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  For Basic Monitoring:                                                       │
│  • Micrometer (already in Spring Boot)                                      │
│  • MongoDB indexes on statistics fields                                     │
│                                                                              │
│  For Alerting:                                                               │
│  • Notification service (email/push)                                        │
│  • Scheduled job infrastructure (Spring @Scheduled)                        │
│                                                                              │
│  For AI Suggestions:                                                         │
│  • Transaction history access (read CashFlow data)                          │
│  • Fuzzy string matching library (optional)                                 │
│  • Statistical analysis utilities                                           │
│                                                                              │
│  For ML (Future):                                                            │
│  • ML platform (AWS SageMaker / GCP ML / custom)                           │
│  • Training data pipeline                                                    │
│  • Model serving infrastructure                                             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Appendix: Quick Reference

### A.1 Metrics Summary

| Metric | Type | Description |
|--------|------|-------------|
| `recurring_rules_count{status}` | Gauge | Number of rules by status |
| `recurring_rules_generation_attempts{status}` | Counter | Generation attempts |
| `recurring_rules_failures_by_type{type}` | Counter | Failures by error type |
| `recurring_rules_auto_paused{reason}` | Counter | Auto-pause events |
| `recurring_rules_http_duration` | Timer | HTTP call latency |
| `recurring_rules_match_rate` | Distribution | Match rate distribution |

### A.2 Alert Thresholds

| Alert | Threshold | Severity |
|-------|-----------|----------|
| High failure rate | >10% in 5 min | Warning |
| Many failed rules | >5 rules | Critical |
| API slow | p95 > 5s | Warning |
| Low match rate | <50% | Info (weekly) |
| Consecutive failures | >=3 | Warning |
| Auto-pause | any | Info (immediate) |

### A.3 AI Detection Thresholds

| Parameter | Value | Description |
|-----------|-------|-------------|
| MIN_OCCURRENCES | 3 | Minimum transactions to suggest |
| DATE_TOLERANCE_DAYS | 5 | Acceptable date variance |
| AMOUNT_TOLERANCE_PERCENT | 5% | Acceptable amount variance |
| CONFIDENCE_THRESHOLD | 70% | Minimum confidence to show |
