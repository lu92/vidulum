# Recurring Rules - Edge Cases, Error Scenarios & Business Logic Analysis

**Data utworzenia:** 2026-02-25
**Status:** Analiza - wykryte luki w designie
**Autor:** Claude Code + User
**Powiązane dokumenty:**
- `2026-02-14-recurring-rule-engine-design.md` (funkcjonalny design)
- `2026-02-25-recurring-rules-microservice-architecture.md` (architektura)

---

## Spis treści

1. [Relacja z kategoriami CashFlow](#1-relacja-z-kategoriami-cashflow)
2. [Error Scenarios - Co może pójść nie tak](#2-error-scenarios---co-może-pójść-nie-tak)
3. [Brakujące User Journeys](#3-brakujące-user-journeys)
4. [Orphaned Data - Osierocone dane](#4-orphaned-data---osierocone-dane)
5. [Consistency & Recovery](#5-consistency--recovery)
6. [Brakujące UI Mockupy](#6-brakujące-ui-mockupy)
7. [Rekomendacje implementacyjne](#7-rekomendacje-implementacyjne)

---

## 1. Relacja z kategoriami CashFlow

### 1.1 Problem: Kategoria może zostać zarchiwizowana

**Scenariusz:**
1. User tworzy rule "Czynsz" z kategorią "Mieszkanie"
2. Rule generuje 12 ExpectedCashChanges
3. User archiwizuje kategorię "Mieszkanie" w CashFlow
4. Przychodzi MonthRollover - rule próbuje wygenerować kolejne transakcje

**Pytania:**
- Czy rule powinno przestać generować?
- Czy user powinien dostać powiadomienie?
- Co z istniejącymi EXPECTED transakcjami?

### 1.2 Analiza Use Cases

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CATEGORY LIFECYCLE vs RECURRING RULE                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  CASE 1: Kategoria zarchiwizowana PRZED utworzeniem rule                    │
│  ─────────────────────────────────────────────────────────────────────────  │
│  User próbuje utworzyć rule → API zwraca błąd walidacji                     │
│  "Category 'Mieszkanie' is archived. Please unarchive or choose another."  │
│                                                                              │
│  UI: Dropdown z kategoriami NIE pokazuje zarchiwizowanych                   │
│  Backend: Walidacja przy CreateRecurringRuleCommand                         │
│                                                                              │
│  ──────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  CASE 2: Kategoria zarchiwizowana PO utworzeniu rule                        │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Opcja A: BLOCK - Nie pozwól archiwizować kategorii z active rules         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Cannot Archive Category                                        [×]  │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                      │   │
│  │ Category "Mieszkanie" cannot be archived because it is used by:     │   │
│  │                                                                      │   │
│  │ • Recurring rule "Czynsz" (active, 12 pending transactions)        │   │
│  │ • Recurring rule "Media" (active, 12 pending transactions)         │   │
│  │                                                                      │   │
│  │ Please pause or delete these rules first.                           │   │
│  │                                                                      │   │
│  │ [Cancel]                              [View Rules]                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  Opcja B: WARN + AUTO-PAUSE - Pozwól, ale auto-pausuj rules                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Archive Category                                               [×]  │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                      │   │
│  │ ⚠️ Category "Mieszkanie" is used by 2 active recurring rules.       │   │
│  │                                                                      │   │
│  │ If you archive this category:                                        │   │
│  │ • Rules will be automatically PAUSED                                │   │
│  │ • No new transactions will be generated                             │   │
│  │ • Existing transactions will remain unchanged                       │   │
│  │                                                                      │   │
│  │ Affected rules:                                                      │   │
│  │ • Czynsz (will be paused)                                           │   │
│  │ • Media (will be paused)                                            │   │
│  │                                                                      │   │
│  │ [Cancel]                              [Archive & Pause Rules]        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  Opcja C: ALLOW + FAIL SILENTLY (ZŁA OPCJA - nie rób tego)                 │
│  Rule próbuje generować → HTTP 400 → log error → user nie wie              │
│                                                                              │
│  ══════════════════════════════════════════════════════════════════════════│
│  REKOMENDACJA: Opcja B (WARN + AUTO-PAUSE)                                 │
│  ══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  ──────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  CASE 3: Kategoria USUNIĘTA (nie tylko archived)                           │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  W obecnym designie kategorie NIE SĄ usuwane, tylko archiwizowane.         │
│  Jeśli kiedyś dodamy hard delete:                                          │
│  → Rule MUSI zostać ended/deleted                                          │
│  → Istniejące EXPECTED transakcje: zachowaj z orphaned categoryName        │
│                                                                              │
│  ──────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  CASE 4: Kategoria odarchiwizowana                                          │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Jeśli rule było PAUSED z powodu archiwizacji:                             │
│  → Pokaż notification: "Category 'Mieszkanie' is now active.               │
│     Would you like to resume rule 'Czynsz'?"                               │
│  → User decyduje czy resume                                                 │
│                                                                              │
│  Automatyczny resume: NIE (user może mieć powód dla pause)                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Nowe pole w RecurringRule

```java
public class RecurringRule {
    // ... existing fields

    private PauseReason pauseReason;  // NEW

    public enum PauseReason {
        MANUAL,                    // User clicked "Pause"
        CATEGORY_ARCHIVED,         // Category was archived
        CASHFLOW_CLOSED,          // CashFlow was closed
        GENERATION_FAILED         // HTTP call failed repeatedly
    }
}
```

### 1.4 Walidacja przy tworzeniu rule

```java
// W CreateRecurringRuleCommandHandler

// 1. Sprawdź czy kategoria istnieje
List<CategoryInfo> categories = cashFlowClient.getCategories(cmd.cashFlowId(), cmd.type());

CategoryInfo category = categories.stream()
    .filter(c -> c.name().equals(cmd.categoryName()))
    .findFirst()
    .orElseThrow(() -> new CategoryDoesNotExistException(cmd.categoryName()));

// 2. Sprawdź czy kategoria nie jest archived
if (category.isArchived()) {
    throw new CategoryArchivedException(cmd.categoryName());
}

// 3. Sprawdź czy typ się zgadza (INFLOW kategoria dla INFLOW rule)
if (category.type() != cmd.type()) {
    throw new CategoryTypeMismatchException(
        cmd.categoryName(),
        category.type(),
        cmd.type()
    );
}
```

---

## 2. Error Scenarios - Co może pójść nie tak

### 2.1 HTTP Call do CashFlow API fails

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         HTTP FAILURE SCENARIOS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO 1: CashFlow service temporarily unavailable                       │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Timeline:                                                                   │
│  1. User creates rule "Czynsz"                                              │
│  2. RecurringRuleCreatedEvent emitted                                       │
│  3. EventHandler tries POST /expected-cash-changes/batch                    │
│  4. HTTP 503 Service Unavailable                                            │
│                                                                              │
│  Problem: Rule saved, but no transactions generated                         │
│                                                                              │
│  Solution: Retry with exponential backoff + Dead Letter Queue              │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         RETRY STRATEGY                               │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                      │   │
│  │  Attempt 1: immediate                                                │   │
│  │  Attempt 2: wait 1 second                                            │   │
│  │  Attempt 3: wait 2 seconds                                           │   │
│  │  Attempt 4: wait 4 seconds                                           │   │
│  │  Attempt 5: wait 8 seconds                                           │   │
│  │  ---                                                                 │   │
│  │  After 5 failures:                                                   │   │
│  │    → Mark rule as GENERATION_PENDING                                 │   │
│  │    → Store failure info in rule                                      │   │
│  │    → Send to DLQ for manual retry                                   │   │
│  │    → Log ERROR with full context                                     │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ──────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SCENARIO 2: CashFlow returns 400 Bad Request                               │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Possible causes:                                                           │
│  • Category no longer exists / archived                                     │
│  • CashFlow in wrong state (CLOSED)                                        │
│  • Date outside allowed range                                               │
│                                                                              │
│  This is NOT a transient error - don't retry!                              │
│                                                                              │
│  Solution:                                                                   │
│  1. Parse error response                                                    │
│  2. Auto-pause rule with reason                                             │
│  3. Notify user                                                             │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ⚠️ Rule "Czynsz" generation failed                            [×]   │   │
│  │                                                                      │   │
│  │ Could not generate transactions:                                     │   │
│  │ Category "Mieszkanie" has been archived.                            │   │
│  │                                                                      │   │
│  │ The rule has been paused automatically.                              │   │
│  │                                                                      │   │
│  │ [View Rule]  [Unarchive Category]                                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ──────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SCENARIO 3: CashFlow returns 404 Not Found                                 │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  CashFlow was deleted!                                                      │
│                                                                              │
│  Solution:                                                                   │
│  1. Mark rule as ENDED with reason CASHFLOW_DELETED                        │
│  2. Don't retry                                                             │
│  3. Keep rule for audit purposes (soft delete)                             │
│                                                                              │
│  ──────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SCENARIO 4: Partial batch success                                          │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  POST /batch with 12 items:                                                 │
│  → 10 created successfully                                                  │
│  → 2 failed (e.g., duplicate dueDate)                                      │
│                                                                              │
│  Current CashFlow API: ALL OR NOTHING (transaction)                        │
│                                                                              │
│  Jeśli kiedyś będzie partial success:                                       │
│  → Response musi zawierać: created[], failed[]                             │
│  → Rule musi zapisać które się udały                                        │
│  → Retry tylko failed                                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Nowe pole: generationStatus

```java
public class RecurringRule {
    // ... existing fields

    private GenerationStatus generationStatus;  // NEW
    private String lastGenerationError;         // NEW
    private ZonedDateTime lastGenerationAttempt; // NEW
    private int failedAttempts;                  // NEW

    public enum GenerationStatus {
        IDLE,              // Nothing pending
        PENDING,           // Generation scheduled
        IN_PROGRESS,       // Currently generating
        FAILED,            // Last attempt failed
        SUCCESS            // Last attempt succeeded
    }
}
```

### 2.3 Failed Generation Recovery Job

```java
@Component
public class FailedGenerationRecoveryJob {

    /**
     * Periodically retry failed rule generations.
     * Runs every hour.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void retryFailedGenerations() {
        List<RecurringRule> failedRules = ruleRepository.findByGenerationStatus(
            GenerationStatus.FAILED
        );

        for (RecurringRule rule : failedRules) {
            // Skip if too many attempts
            if (rule.getFailedAttempts() >= MAX_RETRY_ATTEMPTS) {
                log.warn("Rule {} exceeded max retry attempts, marking as PAUSED",
                    rule.getId());
                rule.pause(PauseReason.GENERATION_FAILED);
                ruleRepository.save(rule);
                notifyUser(rule, "Rule paused due to repeated generation failures");
                continue;
            }

            // Check if enough time passed (exponential backoff)
            Duration backoff = calculateBackoff(rule.getFailedAttempts());
            if (rule.getLastGenerationAttempt().plus(backoff).isAfter(ZonedDateTime.now())) {
                continue; // Not yet
            }

            try {
                generateForRule(rule);
                rule.setGenerationStatus(GenerationStatus.SUCCESS);
                rule.setFailedAttempts(0);
                rule.setLastGenerationError(null);
            } catch (Exception e) {
                rule.setFailedAttempts(rule.getFailedAttempts() + 1);
                rule.setLastGenerationError(e.getMessage());
                rule.setLastGenerationAttempt(ZonedDateTime.now());
                log.error("Retry failed for rule {}: {}", rule.getId(), e.getMessage());
            }

            ruleRepository.save(rule);
        }
    }

    private Duration calculateBackoff(int attempts) {
        // 1h, 2h, 4h, 8h, 16h, 24h (cap)
        long hours = Math.min((long) Math.pow(2, attempts), 24);
        return Duration.ofHours(hours);
    }
}
```

---

## 3. Brakujące User Journeys

### 3.1 CashFlow Deletion

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              USER JOURNEY: Usuwanie CashFlow z aktywnymi rules              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  CURRENT STATE: Brak designu!                                               │
│                                                                              │
│  PROPOSED FLOW:                                                              │
│                                                                              │
│  1. User klika "Delete CashFlow"                                            │
│                                                                              │
│  2. System sprawdza czy są active rules:                                     │
│     → Jeśli nie → standardowy flow usuwania                                 │
│     → Jeśli tak → specjalny dialog                                          │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Delete CashFlow                                               [×]   │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                      │   │
│  │ ⚠️ This CashFlow has 5 active recurring rules:                      │   │
│  │                                                                      │   │
│  │   • Czynsz (2,000 PLN monthly)                                      │   │
│  │   • Netflix (29 PLN monthly)                                        │   │
│  │   • Gym Membership (150 PLN monthly) - PAUSED                       │   │
│  │   • Salary (8,500 PLN monthly)                                      │   │
│  │   • Phone Bill (79 PLN monthly)                                     │   │
│  │                                                                      │   │
│  │ ─────────────────────────────────────────────────────────────────── │   │
│  │                                                                      │   │
│  │ Deleting this CashFlow will:                                         │   │
│  │ ✓ Delete all 5 recurring rules                                      │   │
│  │ ✓ Delete all generated transactions (156 total)                     │   │
│  │ ✓ Delete all categories and budgets                                 │   │
│  │                                                                      │   │
│  │ This action cannot be undone.                                        │   │
│  │                                                                      │   │
│  │ ─────────────────────────────────────────────────────────────────── │   │
│  │                                                                      │   │
│  │ Type "DELETE" to confirm:                                            │   │
│  │ ┌───────────────────────────────────────────────────────────────┐   │   │
│  │ │                                                               │   │   │
│  │ └───────────────────────────────────────────────────────────────┘   │   │
│  │                                                                      │   │
│  │ [Cancel]                                    [Delete CashFlow]        │   │
│  │                                              ^^^^^^^^^^^^^^^^        │   │
│  │                                              czerwony, disabled      │   │
│  │                                              until "DELETE" typed    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  3. Po potwierdzeniu:                                                        │
│     a) CashFlow module emits: CashFlowDeletedEvent                          │
│     b) recurring_rules listens to event                                     │
│     c) All rules for this CashFlow are hard-deleted                         │
│     d) (No need to delete transactions - CashFlow handles that)            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 CashFlow Closed (status change)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│               USER JOURNEY: CashFlow zmienia status na CLOSED               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: User zamyka CashFlow (archiwizuje cały budżet)                   │
│                                                                              │
│  Co powinno się stać z rules?                                               │
│                                                                              │
│  Opcja A: Auto-END wszystkie rules                                          │
│  → Rules zakończone z reason: CASHFLOW_CLOSED                               │
│  → Generated transactions pozostają (historical data)                       │
│                                                                              │
│  Opcja B: Auto-PAUSE wszystkie rules                                        │
│  → Rules wstrzymane z reason: CASHFLOW_CLOSED                               │
│  → Jeśli user reopens CashFlow, może resume rules                          │
│                                                                              │
│  REKOMENDACJA: Opcja B (PAUSE) - bardziej odwracalne                        │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Close CashFlow                                                [×]   │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                      │   │
│  │ Close "Budżet domowy 2026"?                                         │   │
│  │                                                                      │   │
│  │ This CashFlow has 5 active recurring rules.                         │   │
│  │ They will be automatically paused.                                   │   │
│  │                                                                      │   │
│  │ You can reopen this CashFlow later and resume the rules.            │   │
│  │                                                                      │   │
│  │ [Cancel]                                       [Close CashFlow]      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  EVENT FLOW:                                                                 │
│  1. CashFlow emits: CashFlowClosedEvent(cashFlowId)                         │
│  2. recurring_rules listens:                                                 │
│     for (rule : findActiveByCashFlowId(cashFlowId)) {                       │
│         rule.pause(PauseReason.CASHFLOW_CLOSED);                            │
│         save(rule);                                                          │
│     }                                                                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.3 Category Renamed

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    USER JOURNEY: Zmiana nazwy kategorii                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PROBLEM: Rule przechowuje categoryName jako String                         │
│           Jeśli user zmieni nazwę kategorii, rule ma stare dane            │
│                                                                              │
│  OPCJE:                                                                      │
│                                                                              │
│  Opcja A: Przechowuj CategoryId zamiast CategoryName                        │
│  → Rule używa ID, nie nazwy                                                  │
│  → Rename kategorii nie wpływa na rule                                      │
│  → Problem: CategoryId musi być w common/ (dependency)                      │
│                                                                              │
│  Opcja B: CashFlow emituje event CategoryRenamedEvent                       │
│  → recurring_rules słucha i aktualizuje wszystkie rules                     │
│  → Dodatkowa kompleksowość                                                   │
│                                                                              │
│  Opcja C: Rule zawsze pobiera aktualną nazwę z CashFlow                     │
│  → GET /categories przy każdym renderowaniu                                 │
│  → Performance hit                                                           │
│                                                                              │
│  REKOMENDACJA: Opcja A (CategoryId)                                         │
│                                                                              │
│  Ale to wymaga:                                                              │
│  1. Dodania CategoryId do common/                                           │
│  2. Zmiany w RecurringRule: categoryName → categoryId                       │
│  3. Zmiany w CashFlow API: zwracaj categoryId w responses                   │
│                                                                              │
│  ALTERNATYWA (prostsza): Nie pozwalaj rename kategorii z active rules      │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Cannot Rename Category                                        [×]   │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                      │   │
│  │ Category "Mieszkanie" is used by active recurring rules:            │   │
│  │ • Czynsz                                                            │   │
│  │ • Media                                                             │   │
│  │                                                                      │   │
│  │ Please pause these rules before renaming the category.              │   │
│  │                                                                      │   │
│  │ [OK]                                                                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.4 Currency Change

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    USER JOURNEY: CashFlow zmienia walutę                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PROBLEM: Rule ma kwotę w PLN, CashFlow zmienia na EUR                      │
│                                                                              │
│  CURRENT DESIGN: CashFlow NIE POZWALA na zmianę waluty po utworzeniu       │
│  → Problem nie istnieje                                                     │
│                                                                              │
│  ALE: Rule może być stworzone z inną walutą niż CashFlow                    │
│  → Walidacja przy CreateRecurringRule:                                      │
│                                                                              │
│  ```java                                                                     │
│  CashFlowInfo info = cashFlowClient.getCashFlowInfo(cmd.cashFlowId());     │
│  if (!cmd.amount().currency().equals(info.currency())) {                   │
│      throw new CurrencyMismatchException(                                   │
│          cmd.amount().currency(),                                           │
│          info.currency()                                                    │
│      );                                                                      │
│  }                                                                           │
│  ```                                                                         │
│                                                                              │
│  UI: Dropdown z walutą disabled, pokazuje walutę CashFlow                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Orphaned Data - Osierocone dane

### 4.1 Rule deleted, transactions remain

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         ORPHANED TRANSACTIONS                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: User deletes rule but keeps transactions                         │
│                                                                              │
│  Data state:                                                                │
│  • RecurringRule: DELETED (soft delete)                                     │
│  • CashChange[12]: exist with sourceRuleId pointing to deleted rule        │
│                                                                              │
│  PROBLEMS:                                                                   │
│  1. UI "View Generated Transactions" - rule doesn't exist                  │
│  2. Editing transaction - should show "Generated from: [Deleted Rule]"     │
│  3. Reports - should transactions be counted?                               │
│                                                                              │
│  SOLUTIONS:                                                                  │
│                                                                              │
│  1. Transaction view:                                                       │
│     ┌────────────────────────────────────────────────────────────────┐     │
│     │ ● Mar 10, 2026                              2,000 PLN          │     │
│     │   EXPECTED                                                      │     │
│     │   Originally from: Czynsz (rule deleted)                       │     │
│     │                     ^^^^^^^^^^^^^^^^^^^^^^                      │     │
│     │                     szary tekst, italic                         │     │
│     └────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  2. Clean orphaned transactions job (optional):                             │
│     - After 30 days, if sourceRuleId points to deleted rule               │
│     - AND transaction is still EXPECTED (not confirmed)                    │
│     - Clear sourceRuleId (transaction becomes "manual")                    │
│                                                                              │
│  3. Reports: Include orphaned transactions normally                         │
│     - They are valid expected expenses                                      │
│     - Source rule info is just metadata                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 CashFlow deleted, rules remain

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ORPHANED RULES                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: CashFlow hard-deleted, rules still exist                         │
│                                                                              │
│  PROBLEM: Rules point to non-existent CashFlow                              │
│                                                                              │
│  SOLUTION: Event-driven cleanup                                              │
│                                                                              │
│  1. CashFlow emits: CashFlowDeletedEvent(cashFlowId)                        │
│                                                                              │
│  2. recurring_rules listens:                                                 │
│     @KafkaListener(topics = "cash_flow")                                    │
│     void onCashFlowDeleted(CashFlowDeletedEvent event) {                    │
│         List<RecurringRule> rules = ruleRepository                          │
│             .findByCashFlowId(event.cashFlowId());                          │
│                                                                              │
│         for (RecurringRule rule : rules) {                                  │
│             rule.markAsOrphaned();  // soft state                           │
│             // OR: hard delete                                               │
│             ruleRepository.save(rule);  // or delete()                      │
│         }                                                                    │
│     }                                                                        │
│                                                                              │
│  3. Cleanup job (belt & suspenders):                                         │
│     - Run daily                                                              │
│     - For each rule, check if CashFlow exists                               │
│     - If not, mark as orphaned/delete                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Consistency & Recovery

### 5.1 Transaction saga pattern

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CREATE RULE - SAGA PATTERN                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Problem: Rule creation involves multiple steps that can fail              │
│                                                                              │
│  Steps:                                                                      │
│  1. Validate rule data                                                       │
│  2. Save rule to MongoDB                                                     │
│  3. Call CashFlow API to create transactions                                │
│  4. Update rule with generation info                                        │
│                                                                              │
│  What if step 3 fails?                                                       │
│  → Rule exists but no transactions generated                                │
│                                                                              │
│  SOLUTION: Outbox pattern + eventual consistency                            │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        OUTBOX PATTERN                                │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │                                                                      │   │
│  │  1. BEGIN TRANSACTION (MongoDB)                                      │   │
│  │     - Save RecurringRule with status=PENDING_GENERATION              │   │
│  │     - Save OutboxEvent: { type: GENERATE, ruleId, payload }         │   │
│  │  2. COMMIT                                                           │   │
│  │                                                                      │   │
│  │  3. Outbox processor (async):                                        │   │
│  │     - Read OutboxEvent                                               │   │
│  │     - Call CashFlow API                                              │   │
│  │     - If success: update rule status=ACTIVE, delete OutboxEvent     │   │
│  │     - If failure: increment retry count, wait, retry                │   │
│  │                                                                      │   │
│  │  Benefits:                                                           │   │
│  │  - Rule always saved atomically with intent                          │   │
│  │  - Generation is retried until success                               │   │
│  │  - User sees rule immediately (with "Generating..." status)         │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  SIMPLER ALTERNATIVE: Synchronous with rollback                             │
│                                                                              │
│  try {                                                                       │
│      RecurringRule rule = createRule(cmd);                                  │
│      ruleRepository.save(rule);  // Step 1                                  │
│                                                                              │
│      BatchCreateResponse response = cashFlowClient.createBatch(...);       │
│      rule.recordGeneration(response);                                       │
│      ruleRepository.save(rule);  // Step 2                                  │
│                                                                              │
│  } catch (CashFlowApiException e) {                                         │
│      // Rollback: delete the rule we just created                          │
│      ruleRepository.delete(rule);                                           │
│      throw new RuleCreationFailedException(e);                              │
│  }                                                                           │
│                                                                              │
│  Problem z tym podejściem:                                                   │
│  - Nie jest w pełni transakcyjne (rule może być widoczne przez chwilę)    │
│  - Jeśli delete się nie uda, zostaje śmieć                                  │
│                                                                              │
│  REKOMENDACJA: Use Outbox pattern for production                            │
│                Use simple approach for MVP                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Idempotency

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            IDEMPOTENCY                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Problem: Retry może spowodować duplikaty                                   │
│                                                                              │
│  Scenario:                                                                   │
│  1. POST /expected-cash-changes/batch with 12 items                        │
│  2. CashFlow creates 12 transactions                                        │
│  3. Response times out (but transactions were created!)                     │
│  4. recurring_rules retries                                                  │
│  5. CashFlow creates 12 MORE transactions                                   │
│  6. User has 24 duplicate transactions!                                     │
│                                                                              │
│  SOLUTIONS:                                                                  │
│                                                                              │
│  Solution A: Idempotency Key                                                 │
│  ─────────────────────────────────────────────────────────────────────────  │
│  recurring_rules sends:                                                      │
│  POST /expected-cash-changes/batch                                          │
│  X-Idempotency-Key: {ruleId}-{generationId}-{timestamp}                    │
│                                                                              │
│  CashFlow stores idempotency key, returns same response for duplicates     │
│                                                                              │
│  Solution B: Unique constraint on (cashFlowId, sourceRuleId, dueDate)      │
│  ─────────────────────────────────────────────────────────────────────────  │
│  CashFlow rejects duplicates with 409 Conflict                              │
│  recurring_rules treats 409 as success (already created)                    │
│                                                                              │
│  REKOMENDACJA: Solution B (simpler, database-level guarantee)               │
│                                                                              │
│  MongoDB unique index:                                                       │
│  db.cash_changes.createIndex(                                               │
│      { "cashFlowId": 1, "sourceRuleId": 1, "dueDate": 1 },                 │
│      { unique: true, partialFilterExpression: { sourceRuleId: { $ne: null } } }│
│  )                                                                           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Brakujące UI Mockupy

### 6.1 Generation Failed Alert

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              UI: Alert when rule generation failed                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Banner na liście rules:                                                     │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ ⚠️ 2 rules have generation issues                         [View All] │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  Rule card z error state:                                                    │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │ ⚠️ Czynsz                                           2,000 PLN       │    │
│  │    ^^^^                                                             │    │
│  │    żółta ikona warning                                              │    │
│  │                                                                      │    │
│  │ Generation failed · Last attempt: 2 hours ago                       │    │
│  │ Error: Category "Mieszkanie" is archived                           │    │
│  │                                                                      │    │
│  │ [Retry Now]  [Fix Category]  [Pause Rule]                          │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Rule with Pending Generation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              UI: Rule in pending generation state                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │ 🔄 Czynsz                                           2,000 PLN       │    │
│  │    ^^                                                               │    │
│  │    spinning icon                                                    │    │
│  │                                                                      │    │
│  │ Generating transactions...                                          │    │
│  │ ████████░░░░░░░░░░░░░░░░░░░░░ 4/12                                 │    │
│  │                                                                      │    │
│  │ [Cancel]                                                             │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  LUB prostsza wersja (bez progress):                                        │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │ 🔄 Czynsz                                           2,000 PLN       │    │
│  │                                                                      │    │
│  │ Setting up rule... This may take a moment.                          │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.3 Orphaned Transactions Warning

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              UI: Orphaned transactions info                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  W transaction details:                                                      │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ Transaction Details                                             [×]  │  │
│  ├──────────────────────────────────────────────────────────────────────┤  │
│  │                                                                      │  │
│  │ Name:        Czynsz                                                 │  │
│  │ Amount:      2,000 PLN                                              │  │
│  │ Category:    Mieszkanie                                             │  │
│  │ Due Date:    March 10, 2026                                         │  │
│  │ Status:      EXPECTED                                               │  │
│  │                                                                      │  │
│  │ ─────────────────────────────────────────────────────────────────── │  │
│  │                                                                      │  │
│  │ ℹ️ This transaction was generated by rule "Czynsz"                  │  │
│  │    which has since been deleted.                                    │  │
│  │    ^^^^^^^^^^^^^^^^^^^^^^^^^^^                                      │  │
│  │    szary tekst, informacyjny                                        │  │
│  │                                                                      │  │
│  │ [Edit]  [Confirm]  [Delete]                                         │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.4 Category Archived - Rule Paused Notification

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              UI: Notification when rules auto-paused                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Email / In-app notification:                                               │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ ⚠️ 2 recurring rules have been paused                          [×]  │  │
│  │                                                                      │  │
│  │ Category "Mieszkanie" was archived, affecting:                       │  │
│  │                                                                      │  │
│  │ • Czynsz (2,000 PLN monthly) - paused                               │  │
│  │ • Media (350 PLN monthly) - paused                                  │  │
│  │                                                                      │  │
│  │ To continue generating transactions:                                 │  │
│  │ 1. Unarchive the category, or                                       │  │
│  │ 2. Update the rules to use a different category                     │  │
│  │                                                                      │  │
│  │ [View Rules]  [Unarchive Category]                                   │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.5 Month Rollover with Failed Rules

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              UI: Month rollover summary with issues                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  After month rollover completes:                                            │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ ✓ March 2026 closed successfully                               [×]  │  │
│  │                                                                      │  │
│  │ Recurring rules summary:                                             │  │
│  │ • 5 rules generated transactions for April                          │  │
│  │ • 2 rules skipped (paused)                                          │  │
│  │ • 1 rule failed ⚠️                                                   │  │
│  │                                                                      │  │
│  │ Failed: "Czynsz" - Category archived                                │  │
│  │                                                                      │  │
│  │ [View Details]  [Dismiss]                                            │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Rekomendacje implementacyjne

### 7.1 Priorytetyzacja

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      IMPLEMENTATION PRIORITY                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  MUST HAVE (MVP):                                                            │
│  ════════════════                                                            │
│  □ Category validation przy tworzeniu rule                                  │
│  □ Basic retry logic (3 attempts) dla HTTP calls                           │
│  □ CashFlowDeletedEvent handling (cleanup rules)                            │
│  □ Unique constraint na (cashFlowId, sourceRuleId, dueDate)                │
│  □ Basic error states w UI (failed, pending)                               │
│                                                                              │
│  SHOULD HAVE (v1.1):                                                         │
│  ══════════════════                                                          │
│  □ Category archived → auto-pause rules with notification                   │
│  □ CashFlowClosedEvent → auto-pause rules                                   │
│  □ Failed generation recovery job                                           │
│  □ PauseReason tracking                                                      │
│  □ GenerationStatus tracking                                                 │
│  □ Orphaned transactions UI (show "rule deleted" info)                     │
│                                                                              │
│  NICE TO HAVE (future):                                                      │
│  ══════════════════════                                                      │
│  □ Outbox pattern for guaranteed delivery                                   │
│  □ Category renamed → update rules                                          │
│  □ Notifications (email/push) for rule issues                              │
│  □ Audit log for all rule operations                                        │
│  □ Category unarchived → suggest resume rules                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Nowe eventy do obsługi

```java
// CashFlow domain events that recurring_rules must handle:

sealed interface CashFlowEvent {
    // Existing
    record MonthRolledOverEvent(...) implements CashFlowEvent {}

    // NEW - must add to CashFlow
    record CashFlowClosedEvent(
        CashFlowId cashFlowId,
        ZonedDateTime closedAt
    ) implements CashFlowEvent {}

    record CashFlowDeletedEvent(
        CashFlowId cashFlowId,
        ZonedDateTime deletedAt
    ) implements CashFlowEvent {}

    record CategoryArchivedEvent(
        CashFlowId cashFlowId,
        CategoryName categoryName,
        ZonedDateTime archivedAt
    ) implements CashFlowEvent {}

    record CategoryUnarchivedEvent(
        CashFlowId cashFlowId,
        CategoryName categoryName,
        ZonedDateTime unarchivedAt
    ) implements CashFlowEvent {}

    // Optional - for category rename handling
    record CategoryRenamedEvent(
        CashFlowId cashFlowId,
        CategoryName oldName,
        CategoryName newName,
        ZonedDateTime renamedAt
    ) implements CashFlowEvent {}
}
```

### 7.3 Checklist przed implementacją

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      PRE-IMPLEMENTATION CHECKLIST                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  □ CashFlow API endpoints exist:                                            │
│    □ POST /expected-cash-changes/batch                                      │
│    □ DELETE /expected-cash-changes?sourceRuleId=X                          │
│    □ GET /categories (with isArchived flag)                                │
│    □ GET /info (with horizon calculation)                                  │
│                                                                              │
│  □ CashFlow emits required events:                                          │
│    □ MonthRolledOverEvent (existing)                                       │
│    □ CategoryArchivedEvent (new or existing?)                              │
│    □ CashFlowClosedEvent (new)                                             │
│    □ CashFlowDeletedEvent (new)                                            │
│                                                                              │
│  □ CashChange has sourceRuleId field:                                       │
│    □ Field added to domain model                                            │
│    □ Field stored in MongoDB                                                │
│    □ Unique index created                                                   │
│                                                                              │
│  □ Common types extracted:                                                   │
│    □ RecurringRuleId in common/                                            │
│    □ CashFlowId in common/ (if not already)                                │
│                                                                              │
│  □ Error handling defined:                                                   │
│    □ API error response format                                              │
│    □ Retry strategy documented                                              │
│    □ DLQ topic exists                                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Appendix: Event Handling Matrix

| CashFlow Event | recurring_rules Action | User Notification |
|----------------|------------------------|-------------------|
| `MonthRolledOverEvent` | Generate next month for active rules | None (silent) |
| `CategoryArchivedEvent` | Pause rules using this category | Yes - explain why paused |
| `CategoryUnarchivedEvent` | Suggest resume for paused rules | Yes - offer to resume |
| `CashFlowClosedEvent` | Pause all rules for this CashFlow | Yes - in close confirmation |
| `CashFlowDeletedEvent` | Delete all rules for this CashFlow | None - part of delete flow |
| `CategoryRenamedEvent` | Update categoryName in rules (if using name, not ID) | None |

---

## Appendix: Brakujące testy

```java
// Test scenarios that MUST be covered:

class RecurringRulesEdgeCasesTest {

    @Test
    void shouldRejectRuleCreationWhenCategoryArchived() {}

    @Test
    void shouldRejectRuleCreationWhenCategoryDoesNotExist() {}

    @Test
    void shouldRejectRuleCreationWhenCurrencyMismatch() {}

    @Test
    void shouldAutoPauseRuleWhenCategoryArchived() {}

    @Test
    void shouldAutoPauseAllRulesWhenCashFlowClosed() {}

    @Test
    void shouldDeleteAllRulesWhenCashFlowDeleted() {}

    @Test
    void shouldRetryGenerationOnTransientHttpError() {}

    @Test
    void shouldPauseRuleAfterMaxRetryAttemptsExceeded() {}

    @Test
    void shouldNotCreateDuplicateTransactionsOnRetry() {}

    @Test
    void shouldHandlePartialBatchSuccess() {}

    @Test
    void shouldShowOrphanedTransactionInfoWhenRuleDeleted() {}

    @Test
    void shouldGenerateNextMonthOnMonthRollover() {}

    @Test
    void shouldSkipPausedRulesOnMonthRollover() {}

    @Test
    void shouldEndRuleWhenMaxOccurrencesReached() {}

    @Test
    void shouldEndRuleWhenEndDateReached() {}
}
```
