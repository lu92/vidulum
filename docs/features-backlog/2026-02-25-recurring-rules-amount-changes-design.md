# Recurring Rules - Amount Changes Design (Proactive + Reactive)

**Data utworzenia:** 2026-02-25
**Status:** Design techniczny i biznesowy
**Autor:** Claude Code + User
**Powiązane dokumenty:**
- `2026-02-25-recurring-rules-edit-delete-alerts-design.md` (edycja reguł)
- `2026-02-25-recurring-rules-technical-solutions.md` (rozwiązania techniczne)
- `2026-02-14-recurring-rule-engine-design.md` (funkcjonalny design)

---

## Spis treści

1. [Problem Statement](#1-problem-statement)
2. [Dual Flow Architecture](#2-dual-flow-architecture)
3. [Proactive Flow - Scheduled Amount Changes](#3-proactive-flow---scheduled-amount-changes)
4. [Reactive Flow - Match Mismatch Detection](#4-reactive-flow---match-mismatch-detection)
5. [Technical Implementation](#5-technical-implementation)
6. [UI Mockups](#6-ui-mockups)
7. [Business Analysis](#7-business-analysis)
8. [Edge Cases](#8-edge-cases)
9. [Implementation Plan](#9-implementation-plan)

---

## 1. Problem Statement

### 1.1 Scenariusz biznesowy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     REAL-WORLD SCENARIOS                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO 1: PODWYŻKA CZYNSZU (znana z góry)                                │
│  ════════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Timeline:                                                                   │
│  • Grudzień 2026: User dostaje pismo - "Od stycznia czynsz 2,200 PLN"      │
│  • User ma rule "Czynsz" = 2,000 PLN                                        │
│  • User ma już EXPECTED na Jan-Jun 2027 (każdy po 2,000 PLN)               │
│                                                                              │
│  Problem: Jak zaktualizować rule i przyszłe EXPECTED?                       │
│                                                                              │
│  ════════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  SCENARIO 2: RACHUNEK ZA PRĄD (zmienna kwota)                               │
│  ════════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Timeline:                                                                   │
│  • Rule "Prąd" = ~150 PLN (szacunek, amountIsEstimate=true)                │
│  • Rzeczywiste rachunki: 132, 145, 167, 189, 134 PLN                        │
│                                                                              │
│  Problem: Jak matchować różne kwoty? Kiedy rule jest "nieaktualna"?        │
│                                                                              │
│  ════════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  SCENARIO 3: SUBSKRYPCJA - CENA WZROSŁA (niespodzianka)                    │
│  ════════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Timeline:                                                                   │
│  • Rule "Netflix" = 29.99 PLN                                               │
│  • Netflix podniósł cenę bez ostrzeżenia → 39.99 PLN                       │
│  • Bank transaction przychodzi z 39.99 PLN                                  │
│                                                                              │
│  Problem: User nie wiedział o podwyżce. System musi to wykryć.             │
│                                                                              │
│  ════════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  SCENARIO 4: RATA KREDYTU - ZMIANA OPROCENTOWANIA                           │
│  ════════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Timeline:                                                                   │
│  • Rule "Kredyt" = 1,850 PLN (rata stała)                                  │
│  • Co 6 miesięcy bank zmienia oprocentowanie                                │
│  • Nowa rata: 1,920 PLN                                                     │
│                                                                              │
│  Problem: Częste, przewidywalne zmiany kwoty.                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Wymagania

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     REQUIREMENTS                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  FUNCTIONAL:                                                                 │
│  ═══════════                                                                 │
│  F1. User może zaplanować zmianę kwoty z wyprzedzeniem                      │
│  F2. System wykrywa mismatch między EXPECTED a bank transaction             │
│  F3. User może zdecydować: match anyway, update rule, ignore                │
│  F4. Zmiany propagują do przyszłych EXPECTED (opcjonalnie)                  │
│  F5. Historia zmian kwoty jest zachowana (audit trail)                      │
│  F6. Preview zmian przed zatwierdzeniem                                     │
│                                                                              │
│  NON-FUNCTIONAL:                                                             │
│  ═══════════════                                                             │
│  NF1. Intuicyjny UI - user friendly                                         │
│  NF2. Nie wymaga wiedzy technicznej                                         │
│  NF3. Szybkie operacje (<500ms)                                             │
│  NF4. Undo możliwe w ciągu 24h                                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Dual Flow Architecture

### 2.1 Kiedy który flow?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     DUAL FLOW DECISION TREE                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                        ┌─────────────────┐                                   │
│                        │ Zmiana kwoty    │                                   │
│                        │ w recurring     │                                   │
│                        │ rule            │                                   │
│                        └────────┬────────┘                                   │
│                                 │                                            │
│                    ┌────────────┴────────────┐                              │
│                    │                         │                               │
│           ┌────────▼────────┐     ┌─────────▼─────────┐                     │
│           │ User WIE        │     │ User NIE WIE      │                     │
│           │ o zmianie       │     │ o zmianie         │                     │
│           │ z wyprzedzeniem │     │ (niespodzianka)   │                     │
│           └────────┬────────┘     └─────────┬─────────┘                     │
│                    │                         │                               │
│           ┌────────▼────────┐     ┌─────────▼─────────┐                     │
│           │  PROACTIVE      │     │  REACTIVE         │                     │
│           │  FLOW           │     │  FLOW             │                     │
│           │                 │     │                   │                     │
│           │  User planuje   │     │  System wykrywa   │                     │
│           │  zmianę przed   │     │  mismatch przy    │                     │
│           │  jej wejściem   │     │  bank transaction │                     │
│           └────────┬────────┘     └─────────┬─────────┘                     │
│                    │                         │                               │
│                    └────────────┬────────────┘                              │
│                                 │                                            │
│                        ┌────────▼────────┐                                   │
│                        │  AMOUNT         │                                   │
│                        │  HISTORY        │                                   │
│                        │  (audit trail)  │                                   │
│                        └─────────────────┘                                   │
│                                                                              │
│  ════════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  PRZYKŁADY:                                                                  │
│                                                                              │
│  PROACTIVE (user zna datę zmiany):                                          │
│  • Czynsz - dostał pismo o podwyżce                                         │
│  • Kredyt - bank informuje o zmianie raty                                   │
│  • Umowa - podpisał aneks ze zmianą kwoty                                   │
│  • Subskrypcja - email o zmianie cennika                                    │
│                                                                              │
│  REACTIVE (niespodzianka):                                                   │
│  • Netflix - podniósł cenę bez ostrzeżenia                                  │
│  • Rachunek za prąd - różni się od szacunku                                 │
│  • Ubezpieczenie - zmiana składki przy odnowieniu                           │
│  • Opłata bankowa - bank zmienił prowizję                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Flow diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     COMPLETE FLOW DIAGRAM                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        PROACTIVE FLOW                                │   │
│  │                                                                      │   │
│  │   User                  System                    Result             │   │
│  │   ─────                 ──────                    ──────             │   │
│  │                                                                      │   │
│  │   1. "Schedule         2. Validate               3. Store           │   │
│  │      amount change"       • date in future          scheduledChange │   │
│  │      2,200 PLN            • amount > 0                               │   │
│  │      from Jan 2027        • no conflict                             │   │
│  │          │                    │                       │              │   │
│  │          │                    │                       │              │   │
│  │          ▼                    ▼                       ▼              │   │
│  │   4. Preview           5. Confirm              6. Regenerate        │   │
│  │      "12 transactions    "Yes, apply"            EXPECTED for       │   │
│  │      will be updated"        │                   affected months    │   │
│  │          │                    │                       │              │   │
│  │          │                    │                       │              │   │
│  │          └────────────────────┴───────────────────────┘              │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        REACTIVE FLOW                                 │   │
│  │                                                                      │   │
│  │   Bank                  System                    User               │   │
│  │   Transaction           Detection                 Decision           │   │
│  │   ───────────           ─────────                 ────────           │   │
│  │                                                                      │   │
│  │   1. Incoming          2. Try match             3. Mismatch         │   │
│  │      2,200 PLN            with EXPECTED            detected!         │   │
│  │      "CZYNSZ"             2,000 PLN                                  │   │
│  │          │                    │                       │              │   │
│  │          │                    │                       │              │   │
│  │          ▼                    ▼                       ▼              │   │
│  │                          4. Show dialog         5. User chooses:     │   │
│  │                             with options           • Match anyway    │   │
│  │                                │                   • Update rule     │   │
│  │                                │                   • Create new      │   │
│  │                                │                       │              │   │
│  │                                │                       │              │   │
│  │                                ▼                       ▼              │   │
│  │                          6. Execute            7. Update history    │   │
│  │                             user choice           & propagate       │   │
│  │                                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Proactive Flow - Scheduled Amount Changes

### 3.1 Use Cases

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     PROACTIVE USE CASES                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  UC-P1: Schedule Single Amount Change                                        │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Actor: User                                                                 │
│  Precondition: Active recurring rule exists                                  │
│                                                                              │
│  Flow:                                                                       │
│  1. User opens rule "Czynsz" (2,000 PLN)                                    │
│  2. User clicks "Schedule amount change"                                    │
│  3. User enters: 2,200 PLN, starting January 2027                           │
│  4. System shows preview:                                                    │
│     • "Dec 2026: 2,000 PLN (unchanged)"                                     │
│     • "Jan 2027+: 2,200 PLN (new)"                                          │
│     • "6 future transactions will be updated"                               │
│  5. User confirms                                                            │
│  6. System updates rule and regenerates affected EXPECTED                   │
│                                                                              │
│  Postcondition: Rule has scheduled change, EXPECTED updated                 │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  UC-P2: Schedule Multiple Changes (Stepped Increase)                        │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario: Kredyt - rata zmienia się co 6 miesięcy                          │
│                                                                              │
│  Flow:                                                                       │
│  1. User opens rule "Kredyt" (1,850 PLN)                                    │
│  2. User adds multiple scheduled changes:                                    │
│     • From Jul 2027: 1,920 PLN                                              │
│     • From Jan 2028: 1,990 PLN                                              │
│  3. System shows timeline preview                                            │
│  4. User confirms                                                            │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  UC-P3: Cancel Scheduled Change                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario: Podwyżka czynszu została odwołana                                │
│                                                                              │
│  Flow:                                                                       │
│  1. User opens rule with scheduled change                                   │
│  2. User clicks "Cancel scheduled change"                                   │
│  3. System asks: "Revert 6 transactions to 2,000 PLN?"                      │
│  4. User confirms                                                            │
│  5. System removes scheduled change and reverts EXPECTED                    │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  UC-P4: Modify Scheduled Change                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario: Podwyżka jest inna niż planowano (2,250 zamiast 2,200)          │
│                                                                              │
│  Flow:                                                                       │
│  1. User opens rule with scheduled change                                   │
│  2. User edits scheduled change: 2,200 → 2,250 PLN                          │
│  3. System shows preview of affected transactions                           │
│  4. User confirms                                                            │
│  5. System updates scheduled change and EXPECTED                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Domain Model

```java
package com.multi.vidulum.recurring_rules.domain;

@Getter
@AllArgsConstructor
public class RecurringRule {
    // ... existing fields ...

    private Money currentAmount;                       // Active amount
    private List<ScheduledAmountChange> scheduledChanges;  // Future changes
    private List<AmountHistoryEntry> amountHistory;        // Past changes (audit)

    // ════════════════════════════════════════════════════════════════════════
    // SCHEDULED CHANGES
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Schedule a future amount change.
     */
    public void scheduleAmountChange(
            Money newAmount,
            YearMonth effectiveFrom,
            String reason
    ) {
        // Validate
        if (effectiveFrom.isBefore(YearMonth.now().plusMonths(1))) {
            throw new InvalidScheduledChangeException(
                "Cannot schedule change for current or past month"
            );
        }

        if (newAmount.isNegativeOrZero()) {
            throw new InvalidAmountException("Amount must be positive");
        }

        // Check for conflicts
        scheduledChanges.stream()
            .filter(sc -> sc.effectiveFrom().equals(effectiveFrom))
            .findAny()
            .ifPresent(existing -> {
                throw new ConflictingScheduledChangeException(
                    "Already have scheduled change for " + effectiveFrom
                );
            });

        // Add scheduled change
        ScheduledAmountChange change = new ScheduledAmountChange(
            UUID.randomUUID().toString(),
            newAmount,
            effectiveFrom,
            reason,
            ZonedDateTime.now(),
            ScheduledChangeStatus.PENDING
        );

        scheduledChanges.add(change);
        scheduledChanges.sort(Comparator.comparing(ScheduledAmountChange::effectiveFrom));

        // Emit event
        registerEvent(new AmountChangeScheduledEvent(
            this.id,
            this.cashFlowId,
            change
        ));
    }

    /**
     * Cancel a scheduled change.
     */
    public void cancelScheduledChange(String changeId) {
        ScheduledAmountChange change = scheduledChanges.stream()
            .filter(sc -> sc.id().equals(changeId))
            .findFirst()
            .orElseThrow(() -> new ScheduledChangeNotFoundException(changeId));

        if (change.status() == ScheduledChangeStatus.APPLIED) {
            throw new CannotCancelAppliedChangeException(changeId);
        }

        scheduledChanges.remove(change);

        registerEvent(new ScheduledChangesCancelledEvent(
            this.id,
            this.cashFlowId,
            changeId
        ));
    }

    /**
     * Apply scheduled changes that are now effective.
     * Called during month rollover.
     */
    public void applyPendingScheduledChanges(YearMonth currentMonth) {
        List<ScheduledAmountChange> toApply = scheduledChanges.stream()
            .filter(sc -> sc.status() == ScheduledChangeStatus.PENDING)
            .filter(sc -> !sc.effectiveFrom().isAfter(currentMonth))
            .sorted(Comparator.comparing(ScheduledAmountChange::effectiveFrom))
            .toList();

        for (ScheduledAmountChange change : toApply) {
            // Record in history
            amountHistory.add(new AmountHistoryEntry(
                currentAmount,
                getLastAmountChangeDate(),
                currentMonth.minusMonths(1),
                "Replaced by scheduled change"
            ));

            // Apply change
            Money oldAmount = currentAmount;
            currentAmount = change.amount();

            // Mark as applied
            int index = scheduledChanges.indexOf(change);
            scheduledChanges.set(index, change.markAsApplied());

            registerEvent(new AmountChangedEvent(
                this.id,
                this.cashFlowId,
                oldAmount,
                currentAmount,
                change.reason(),
                AmountChangeSource.SCHEDULED
            ));
        }
    }

    /**
     * Get effective amount for a specific month.
     * Considers current amount and scheduled changes.
     */
    public Money getEffectiveAmountForMonth(YearMonth month) {
        // Find the most recent scheduled change effective by this month
        return scheduledChanges.stream()
            .filter(sc -> !sc.effectiveFrom().isAfter(month))
            .max(Comparator.comparing(ScheduledAmountChange::effectiveFrom))
            .map(ScheduledAmountChange::amount)
            .orElse(currentAmount);
    }
}

// ════════════════════════════════════════════════════════════════════════════
// VALUE OBJECTS
// ════════════════════════════════════════════════════════════════════════════

public record ScheduledAmountChange(
    String id,
    Money amount,
    YearMonth effectiveFrom,
    String reason,                    // "Podwyżka 2027", "Zmiana oprocentowania"
    ZonedDateTime scheduledAt,
    ScheduledChangeStatus status
) {
    public ScheduledAmountChange markAsApplied() {
        return new ScheduledAmountChange(
            id, amount, effectiveFrom, reason, scheduledAt,
            ScheduledChangeStatus.APPLIED
        );
    }
}

public enum ScheduledChangeStatus {
    PENDING,    // Scheduled, not yet effective
    APPLIED,    // Already applied to current amount
    CANCELLED   // User cancelled before effective date
}

public record AmountHistoryEntry(
    Money amount,
    YearMonth validFrom,
    YearMonth validUntil,      // null = was current until replaced
    String changeReason
) {}

// ════════════════════════════════════════════════════════════════════════════
// EVENTS
// ════════════════════════════════════════════════════════════════════════════

public record AmountChangeScheduledEvent(
    RecurringRuleId ruleId,
    CashFlowId cashFlowId,
    ScheduledAmountChange scheduledChange
) implements RecurringRuleEvent {}

public record ScheduledChangeCancelledEvent(
    RecurringRuleId ruleId,
    CashFlowId cashFlowId,
    String changeId
) implements RecurringRuleEvent {}

public record AmountChangedEvent(
    RecurringRuleId ruleId,
    CashFlowId cashFlowId,
    Money oldAmount,
    Money newAmount,
    String reason,
    AmountChangeSource source
) implements RecurringRuleEvent {}

public enum AmountChangeSource {
    SCHEDULED,      // From scheduled change
    MISMATCH,       // From reactive mismatch flow
    MANUAL_EDIT     // Direct edit
}
```

---

## 4. Reactive Flow - Match Mismatch Detection

### 4.1 Use Cases

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     REACTIVE USE CASES                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  UC-R1: Mismatch Detected - Update Rule Permanently                         │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Actor: System (detection), User (decision)                                 │
│  Trigger: Bank transaction amount differs from EXPECTED                     │
│                                                                              │
│  Flow:                                                                       │
│  1. Bank transaction arrives: 2,200 PLN "CZYNSZ STYCZEŃ"                   │
│  2. System finds matching EXPECTED: 2,000 PLN (rule "Czynsz")              │
│  3. Mismatch detected: +200 PLN (+10%)                                      │
│  4. System shows mismatch dialog with options                               │
│  5. User chooses "Update rule permanently"                                  │
│  6. System asks about future transactions                                   │
│  7. User confirms: "Yes, update future transactions too"                    │
│  8. System:                                                                  │
│     a) Matches bank transaction to EXPECTED                                 │
│     b) Updates rule: 2,000 → 2,200 PLN                                      │
│     c) Updates future EXPECTED transactions                                 │
│     d) Records in amount history                                            │
│                                                                              │
│  Postcondition: Transaction matched, rule updated, history recorded         │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  UC-R2: Mismatch Detected - Match Anyway (One-time Exception)               │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario: Rachunek za prąd - różni się od szacunku, ale to normalne       │
│                                                                              │
│  Flow:                                                                       │
│  1. Bank transaction: 167 PLN "TAURON"                                      │
│  2. EXPECTED: 150 PLN (rule "Prąd", amountIsEstimate=true)                 │
│  3. Mismatch: +17 PLN (+11%)                                                │
│  4. User chooses "Match anyway (one-time difference)"                       │
│  5. System:                                                                  │
│     a) Matches transaction to EXPECTED                                      │
│     b) Keeps rule at 150 PLN                                                │
│     c) Does NOT update future EXPECTED                                      │
│     d) Records mismatch in transaction metadata                             │
│                                                                              │
│  Postcondition: Transaction matched, rule unchanged                         │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  UC-R3: Mismatch Detected - Not Related                                     │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario: Transaction looks similar but is actually different              │
│                                                                              │
│  Flow:                                                                       │
│  1. Bank transaction: 2,200 PLN "CZYNSZ GARAŻU"                            │
│  2. System suggests match to rule "Czynsz" (2,000 PLN)                     │
│  3. User chooses "This is not related to this rule"                         │
│  4. System:                                                                  │
│     a) Does NOT match to EXPECTED                                           │
│     b) Creates standalone CONFIRMED transaction                             │
│     c) EXPECTED remains unmatched (for future real transaction)            │
│                                                                              │
│  Postcondition: Separate transaction created, EXPECTED remains              │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  UC-R4: Mismatch with Estimate Flag                                          │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario: Rule has amountIsEstimate=true (e.g., utility bills)            │
│                                                                              │
│  Flow:                                                                       │
│  1. Rule "Prąd": 150 PLN, amountIsEstimate=true, tolerance=20%             │
│  2. Bank transaction: 175 PLN (within tolerance)                            │
│  3. System auto-matches (within tolerance)                                  │
│  4. No user intervention needed                                              │
│                                                                              │
│  Alternative: Amount outside tolerance                                       │
│  1. Bank transaction: 250 PLN (+67%, outside 20% tolerance)                 │
│  2. System shows mismatch dialog                                            │
│  3. User can: match anyway, update estimate, or reject match               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Matching Algorithm

```java
package com.multi.vidulum.recurring_rules.app;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionMatchingService {

    private final RecurringRuleRepository ruleRepository;
    private final CashFlowServiceClient cashFlowClient;

    /**
     * Attempt to match a bank transaction to an EXPECTED cash change.
     */
    public MatchResult tryMatch(
            CashFlowId cashFlowId,
            BankTransaction transaction
    ) {
        // Find potential matches
        List<ExpectedCashChange> candidates = findMatchCandidates(
            cashFlowId,
            transaction
        );

        if (candidates.isEmpty()) {
            return MatchResult.noMatch();
        }

        // Score each candidate
        List<ScoredMatch> scoredMatches = candidates.stream()
            .map(expected -> scoreMatch(transaction, expected))
            .sorted(Comparator.comparing(ScoredMatch::score).reversed())
            .toList();

        ScoredMatch bestMatch = scoredMatches.get(0);

        // Perfect match - auto-confirm
        if (bestMatch.score() >= 0.95 && bestMatch.amountMatch() == AmountMatchType.EXACT) {
            return MatchResult.perfectMatch(bestMatch.expected());
        }

        // Good match with amount difference
        if (bestMatch.score() >= 0.7) {
            // Check if within tolerance (for estimates)
            if (isWithinTolerance(transaction, bestMatch.expected())) {
                return MatchResult.toleranceMatch(bestMatch.expected());
            }

            // Mismatch - requires user decision
            return MatchResult.mismatch(
                bestMatch.expected(),
                transaction.amount(),
                bestMatch.expected().amount(),
                calculateDifference(transaction.amount(), bestMatch.expected().amount())
            );
        }

        // Low confidence - no match
        return MatchResult.noMatch();
    }

    private List<ExpectedCashChange> findMatchCandidates(
            CashFlowId cashFlowId,
            BankTransaction transaction
    ) {
        // Get EXPECTED transactions in date range
        LocalDate txDate = transaction.operationDate();
        LocalDate rangeStart = txDate.minusDays(7);
        LocalDate rangeEnd = txDate.plusDays(7);

        List<ExpectedCashChange> expected = cashFlowClient.getExpectedCashChanges(
            cashFlowId,
            rangeStart,
            rangeEnd,
            CashChangeStatus.EXPECTED
        );

        // Filter by type (INFLOW/OUTFLOW)
        Type txType = transaction.amount().isPositive() ? Type.INFLOW : Type.OUTFLOW;

        return expected.stream()
            .filter(e -> e.type() == txType)
            .toList();
    }

    private ScoredMatch scoreMatch(
            BankTransaction transaction,
            ExpectedCashChange expected
    ) {
        double score = 0.0;

        // 1. Date proximity (max 0.3)
        long daysDiff = Math.abs(ChronoUnit.DAYS.between(
            transaction.operationDate(),
            expected.dueDate()
        ));
        score += Math.max(0, 0.3 - (daysDiff * 0.05));

        // 2. Amount match (max 0.4)
        AmountMatchType amountMatch = classifyAmountMatch(
            transaction.amount().abs(),
            expected.amount().abs()
        );
        score += switch (amountMatch) {
            case EXACT -> 0.4;
            case WITHIN_TOLERANCE -> 0.3;
            case CLOSE -> 0.2;
            case DIFFERENT -> 0.0;
        };

        // 3. Description/name similarity (max 0.2)
        double nameSimilarity = calculateSimilarity(
            transaction.description(),
            expected.name()
        );
        score += nameSimilarity * 0.2;

        // 4. Source rule match (max 0.1)
        if (expected.sourceRuleId() != null) {
            RecurringRule rule = ruleRepository.findById(expected.sourceRuleId());
            if (rule != null && matchesRuleHints(transaction, rule)) {
                score += 0.1;
            }
        }

        return new ScoredMatch(expected, score, amountMatch);
    }

    private AmountMatchType classifyAmountMatch(Money txAmount, Money expectedAmount) {
        if (txAmount.equals(expectedAmount)) {
            return AmountMatchType.EXACT;
        }

        double diff = Math.abs(
            txAmount.getAmount().doubleValue() - expectedAmount.getAmount().doubleValue()
        );
        double percentage = diff / expectedAmount.getAmount().doubleValue();

        if (percentage <= 0.02) {  // 2% - rounding errors
            return AmountMatchType.EXACT;
        }
        if (percentage <= 0.20) {  // 20% - within tolerance for estimates
            return AmountMatchType.WITHIN_TOLERANCE;
        }
        if (percentage <= 0.50) {  // 50% - close but different
            return AmountMatchType.CLOSE;
        }
        return AmountMatchType.DIFFERENT;
    }

    private boolean isWithinTolerance(BankTransaction tx, ExpectedCashChange expected) {
        if (expected.sourceRuleId() == null) {
            return false;
        }

        RecurringRule rule = ruleRepository.findById(expected.sourceRuleId());
        if (rule == null || !rule.isAmountIsEstimate()) {
            return false;
        }

        Double tolerance = rule.getAmountTolerance();
        if (tolerance == null) {
            tolerance = 0.20;  // Default 20%
        }

        double diff = Math.abs(
            tx.amount().abs().getAmount().doubleValue() -
            expected.amount().abs().getAmount().doubleValue()
        );
        double percentage = diff / expected.amount().abs().getAmount().doubleValue();

        return percentage <= tolerance;
    }
}

// ════════════════════════════════════════════════════════════════════════════
// RESULT TYPES
// ════════════════════════════════════════════════════════════════════════════

public sealed interface MatchResult {

    record PerfectMatch(
        ExpectedCashChange expected
    ) implements MatchResult {}

    record ToleranceMatch(
        ExpectedCashChange expected
    ) implements MatchResult {}

    record Mismatch(
        ExpectedCashChange expected,
        Money actualAmount,
        Money expectedAmount,
        AmountDifference difference
    ) implements MatchResult {}

    record NoMatch() implements MatchResult {}

    static PerfectMatch perfectMatch(ExpectedCashChange e) {
        return new PerfectMatch(e);
    }
    static ToleranceMatch toleranceMatch(ExpectedCashChange e) {
        return new ToleranceMatch(e);
    }
    static Mismatch mismatch(ExpectedCashChange e, Money actual, Money expected, AmountDifference diff) {
        return new Mismatch(e, actual, expected, diff);
    }
    static NoMatch noMatch() {
        return new NoMatch();
    }
}

public record AmountDifference(
    Money absoluteDifference,
    double percentageDifference,
    boolean isIncrease
) {
    public String formatForDisplay() {
        String sign = isIncrease ? "+" : "-";
        return String.format("%s%s (%s%.1f%%)",
            sign,
            absoluteDifference.format(),
            sign,
            Math.abs(percentageDifference * 100)
        );
    }
}

public enum AmountMatchType {
    EXACT,
    WITHIN_TOLERANCE,
    CLOSE,
    DIFFERENT
}

record ScoredMatch(
    ExpectedCashChange expected,
    double score,
    AmountMatchType amountMatch
) {}
```

### 4.3 Mismatch Resolution

```java
package com.multi.vidulum.recurring_rules.app;

@Service
@RequiredArgsConstructor
@Slf4j
public class MismatchResolutionService {

    private final RecurringRuleRepository ruleRepository;
    private final CashFlowServiceClient cashFlowClient;
    private final TransactionPropagationService propagationService;

    /**
     * Resolve a mismatch based on user's choice.
     */
    public MismatchResolutionResult resolve(
            MismatchResolutionRequest request
    ) {
        return switch (request.resolution()) {
            case MATCH_ANYWAY -> resolveMatchAnyway(request);
            case UPDATE_RULE -> resolveUpdateRule(request);
            case NOT_RELATED -> resolveNotRelated(request);
        };
    }

    /**
     * Match anyway - one-time exception.
     * Transaction is matched but rule is not updated.
     */
    private MismatchResolutionResult resolveMatchAnyway(
            MismatchResolutionRequest request
    ) {
        // Match the transaction to EXPECTED
        cashFlowClient.confirmCashChange(
            request.cashFlowId(),
            request.expectedCashChangeId(),
            new ConfirmRequest(
                request.bankTransactionId(),
                request.actualAmount(),  // Use actual amount, not expected
                true  // allowAmountMismatch
            )
        );

        log.info("Matched transaction {} to expected {} with amount mismatch (one-time)",
            request.bankTransactionId(),
            request.expectedCashChangeId());

        return new MismatchResolutionResult(
            MismatchResolution.MATCH_ANYWAY,
            0,  // transactions updated
            null,
            "Transaction matched. Rule amount unchanged."
        );
    }

    /**
     * Update rule permanently.
     * Updates rule and optionally propagates to future transactions.
     */
    private MismatchResolutionResult resolveUpdateRule(
            MismatchResolutionRequest request
    ) {
        RecurringRule rule = ruleRepository.findById(request.ruleId())
            .orElseThrow(() -> new RuleNotFoundException(request.ruleId()));

        Money oldAmount = rule.getCurrentAmount();
        Money newAmount = request.actualAmount();

        // 1. Update rule
        rule.updateAmount(
            newAmount,
            AmountChangeSource.MISMATCH,
            "Updated from mismatch: " + oldAmount.format() + " → " + newAmount.format()
        );
        ruleRepository.save(rule);

        // 2. Match the transaction
        cashFlowClient.confirmCashChange(
            request.cashFlowId(),
            request.expectedCashChangeId(),
            new ConfirmRequest(
                request.bankTransactionId(),
                request.actualAmount(),
                false
            )
        );

        // 3. Propagate to future transactions (if requested)
        int updatedCount = 0;
        if (request.propagateToFuture()) {
            updatedCount = propagationService.updateFutureTransactions(
                rule.getId(),
                request.cashFlowId(),
                newAmount,
                YearMonth.now().plusMonths(1)  // Start from next month
            );
        }

        log.info("Updated rule {} amount from {} to {}, propagated to {} transactions",
            rule.getId(), oldAmount, newAmount, updatedCount);

        return new MismatchResolutionResult(
            MismatchResolution.UPDATE_RULE,
            updatedCount,
            rule.getId(),
            String.format(
                "Rule updated to %s. %d future transactions updated.",
                newAmount.format(),
                updatedCount
            )
        );
    }

    /**
     * Not related - create separate transaction.
     */
    private MismatchResolutionResult resolveNotRelated(
            MismatchResolutionRequest request
    ) {
        // Create standalone CONFIRMED transaction
        CashChangeId newId = cashFlowClient.createConfirmedCashChange(
            request.cashFlowId(),
            new CreateCashChangeRequest(
                request.actualAmount(),
                request.bankTransactionDescription(),
                request.categoryName(),  // User should specify
                request.bankTransactionId()
            )
        );

        log.info("Created standalone transaction {} (not related to expected {})",
            newId, request.expectedCashChangeId());

        return new MismatchResolutionResult(
            MismatchResolution.NOT_RELATED,
            0,
            null,
            "New transaction created. Expected transaction remains for future matching."
        );
    }
}

// ════════════════════════════════════════════════════════════════════════════
// REQUEST/RESPONSE
// ════════════════════════════════════════════════════════════════════════════

public record MismatchResolutionRequest(
    CashFlowId cashFlowId,
    CashChangeId expectedCashChangeId,
    RecurringRuleId ruleId,
    String bankTransactionId,
    Money actualAmount,
    Money expectedAmount,
    MismatchResolution resolution,
    boolean propagateToFuture,
    String bankTransactionDescription,
    String categoryName
) {}

public enum MismatchResolution {
    MATCH_ANYWAY,      // Match with different amount, keep rule as-is
    UPDATE_RULE,       // Update rule to new amount
    NOT_RELATED        // This transaction is not related to the rule
}

public record MismatchResolutionResult(
    MismatchResolution resolution,
    int transactionsUpdated,
    RecurringRuleId ruleId,
    String message
) {}
```

---

## 5. Technical Implementation

### 5.1 MongoDB Schema

```javascript
// recurring_rules collection - extended schema

{
  "_id": "RR10000001",
  "cashFlowId": "CF10000001",
  "userId": "U10000001",
  "name": "Czynsz",
  // ... other existing fields ...

  // ═══════════════════════════════════════════════════════════════════════
  // AMOUNT MANAGEMENT (NEW)
  // ═══════════════════════════════════════════════════════════════════════

  "currentAmount": {
    "amount": 2000.00,
    "currency": "PLN"
  },

  "amountIsEstimate": false,
  "amountTolerance": null,  // null = use default (20% for estimates)

  // Scheduled future changes
  "scheduledChanges": [
    {
      "id": "SC001",
      "amount": { "amount": 2200.00, "currency": "PLN" },
      "effectiveFrom": "2027-01",
      "reason": "Podwyżka 2027",
      "scheduledAt": ISODate("2026-12-15T10:00:00Z"),
      "status": "PENDING"  // PENDING, APPLIED, CANCELLED
    }
  ],

  // History of past amounts (audit trail)
  "amountHistory": [
    {
      "amount": { "amount": 1800.00, "currency": "PLN" },
      "validFrom": "2025-01",
      "validUntil": "2025-12",
      "changeReason": "Initial amount"
    },
    {
      "amount": { "amount": 2000.00, "currency": "PLN" },
      "validFrom": "2026-01",
      "validUntil": null,  // Current
      "changeReason": "Podwyżka 2026"
    }
  ],

  // ... rest of existing fields ...
}

// Indexes
db.recurring_rules.createIndex({
  "scheduledChanges.effectiveFrom": 1,
  "scheduledChanges.status": 1
})
```

### 5.2 API Endpoints

```java
@RestController
@RequestMapping("/api/v1/recurring-rules")
@RequiredArgsConstructor
public class RecurringRuleAmountController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    // ════════════════════════════════════════════════════════════════════════
    // PROACTIVE FLOW ENDPOINTS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Schedule a future amount change.
     */
    @PostMapping("/{ruleId}/scheduled-changes")
    public ResponseEntity<ScheduleAmountChangeResponse> scheduleAmountChange(
            @PathVariable String ruleId,
            @RequestBody @Valid ScheduleAmountChangeRequest request
    ) {
        ScheduleAmountChangeCommand cmd = new ScheduleAmountChangeCommand(
            RecurringRuleId.of(ruleId),
            Money.of(request.amount(), request.currency()),
            YearMonth.parse(request.effectiveFrom()),
            request.reason()
        );

        ScheduleAmountChangeResult result = commandGateway.send(cmd);

        return ResponseEntity.ok(new ScheduleAmountChangeResponse(
            result.changeId(),
            result.previewAffectedTransactions()
        ));
    }

    /**
     * Preview the impact of a scheduled change.
     */
    @PostMapping("/{ruleId}/scheduled-changes/preview")
    public ResponseEntity<ScheduledChangePreview> previewScheduledChange(
            @PathVariable String ruleId,
            @RequestBody @Valid ScheduleAmountChangeRequest request
    ) {
        PreviewScheduledChangeQuery query = new PreviewScheduledChangeQuery(
            RecurringRuleId.of(ruleId),
            Money.of(request.amount(), request.currency()),
            YearMonth.parse(request.effectiveFrom())
        );

        ScheduledChangePreview preview = queryGateway.send(query);
        return ResponseEntity.ok(preview);
    }

    /**
     * Cancel a scheduled change.
     */
    @DeleteMapping("/{ruleId}/scheduled-changes/{changeId}")
    public ResponseEntity<CancelScheduledChangeResponse> cancelScheduledChange(
            @PathVariable String ruleId,
            @PathVariable String changeId,
            @RequestParam(defaultValue = "true") boolean revertTransactions
    ) {
        CancelScheduledChangeCommand cmd = new CancelScheduledChangeCommand(
            RecurringRuleId.of(ruleId),
            changeId,
            revertTransactions
        );

        CancelScheduledChangeResult result = commandGateway.send(cmd);

        return ResponseEntity.ok(new CancelScheduledChangeResponse(
            result.transactionsReverted()
        ));
    }

    /**
     * Get amount history for a rule.
     */
    @GetMapping("/{ruleId}/amount-history")
    public ResponseEntity<AmountHistoryResponse> getAmountHistory(
            @PathVariable String ruleId
    ) {
        GetAmountHistoryQuery query = new GetAmountHistoryQuery(
            RecurringRuleId.of(ruleId)
        );

        AmountHistoryResponse history = queryGateway.send(query);
        return ResponseEntity.ok(history);
    }

    // ════════════════════════════════════════════════════════════════════════
    // REACTIVE FLOW ENDPOINTS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Get potential matches for a bank transaction.
     */
    @PostMapping("/match-candidates")
    public ResponseEntity<MatchCandidatesResponse> getMatchCandidates(
            @RequestBody @Valid MatchCandidatesRequest request
    ) {
        FindMatchCandidatesQuery query = new FindMatchCandidatesQuery(
            CashFlowId.of(request.cashFlowId()),
            new BankTransaction(
                request.transactionId(),
                Money.of(request.amount(), request.currency()),
                request.operationDate(),
                request.description()
            )
        );

        MatchCandidatesResponse candidates = queryGateway.send(query);
        return ResponseEntity.ok(candidates);
    }

    /**
     * Resolve a mismatch.
     */
    @PostMapping("/resolve-mismatch")
    public ResponseEntity<MismatchResolutionResponse> resolveMismatch(
            @RequestBody @Valid MismatchResolutionRequest request
    ) {
        ResolveMismatchCommand cmd = new ResolveMismatchCommand(
            CashFlowId.of(request.cashFlowId()),
            CashChangeId.of(request.expectedCashChangeId()),
            RecurringRuleId.of(request.ruleId()),
            request.bankTransactionId(),
            Money.of(request.actualAmount(), request.currency()),
            request.resolution(),
            request.propagateToFuture()
        );

        MismatchResolutionResult result = commandGateway.send(cmd);

        return ResponseEntity.ok(new MismatchResolutionResponse(
            result.resolution(),
            result.transactionsUpdated(),
            result.message()
        ));
    }
}

// ════════════════════════════════════════════════════════════════════════════
// DTOs
// ════════════════════════════════════════════════════════════════════════════

public record ScheduleAmountChangeRequest(
    @NotNull BigDecimal amount,
    @NotBlank String currency,
    @NotBlank String effectiveFrom,  // "2027-01"
    String reason
) {}

public record ScheduledChangePreview(
    Money currentAmount,
    Money newAmount,
    YearMonth effectiveFrom,
    int affectedTransactions,
    List<AffectedTransaction> transactions,
    Money totalImpactPerMonth
) {}

public record AffectedTransaction(
    String cashChangeId,
    LocalDate dueDate,
    Money currentAmount,
    Money newAmount,
    String monthLabel  // "January 2027"
) {}

public record MatchCandidatesResponse(
    List<MatchCandidate> candidates,
    boolean hasPerfectMatch
) {}

public record MatchCandidate(
    String expectedCashChangeId,
    String ruleName,
    String ruleId,
    Money expectedAmount,
    LocalDate expectedDate,
    double matchScore,
    AmountDifference amountDifference,
    boolean isEstimate,
    Double tolerance
) {}
```

---

## 6. UI Mockups

### 6.1 Proactive Flow - Schedule Amount Change

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     UI: Schedule Amount Change                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  STEP 1: ENTRY POINT (Rule Detail Page)                                     │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Czynsz                                                    [Edit] [⋮]   │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │  Amount: 2,000 PLN                                                    │ │
│  │  Pattern: Monthly, 10th                                               │ │
│  │  Category: Mieszkanie                                                  │ │
│  │                                                                        │ │
│  │  ┌──────────────────────────────────────────────────────────────────┐ │ │
│  │  │ 📅 Scheduled Changes                                              │ │ │
│  │  │                                                                    │ │ │
│  │  │ No scheduled changes.                                             │ │ │
│  │  │                                                                    │ │ │
│  │  │ [+ Schedule Amount Change]                                        │ │ │
│  │  └──────────────────────────────────────────────────────────────────┘ │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  STEP 2: SCHEDULE CHANGE DIALOG                                             │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Schedule Amount Change                                            [×]  │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │  Rule: Czynsz                                                         │ │
│  │  Current Amount: 2,000 PLN                                            │ │
│  │                                                                        │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  New Amount                                                            │ │
│  │  ┌──────────────────────────────────────────────────────────────────┐ │ │
│  │  │ 2,200                                               │ PLN  ▼│    │ │ │
│  │  └──────────────────────────────────────────────────────────────────┘ │ │
│  │  +200 PLN (+10%) from current                                         │ │
│  │                                                                        │ │
│  │  Starting From                                                         │ │
│  │  ┌──────────────────────────────────────────────────────────────────┐ │ │
│  │  │ January 2027                                               ▼│    │ │ │
│  │  └──────────────────────────────────────────────────────────────────┘ │ │
│  │                                                                        │ │
│  │  Reason (optional)                                                     │ │
│  │  ┌──────────────────────────────────────────────────────────────────┐ │ │
│  │  │ Podwyżka czynszu 2027                                            │ │ │
│  │  └──────────────────────────────────────────────────────────────────┘ │ │
│  │                                                                        │ │
│  │  [Cancel]                                    [Preview Changes]         │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  STEP 3: PREVIEW (KLUCZOWY ELEMENT!)                                        │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Preview Changes                                                   [×]  │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │  Rule: Czynsz                                                         │ │
│  │  Change: 2,000 PLN → 2,200 PLN (+10%)                                │ │
│  │  Effective: January 2027                                              │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  📊 Impact Summary                                                     │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐   │ │
│  │  │       6         │    │   +1,200 PLN    │    │   +200 PLN      │   │ │
│  │  │  Transactions   │    │   Total Impact  │    │   Per Month     │   │ │
│  │  │  will be updated│    │   (6 months)    │    │                 │   │ │
│  │  └─────────────────┘    └─────────────────┘    └─────────────────┘   │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  📅 Affected Transactions                                              │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  ┌───────────────────────────────────────────────────────────────────┐│ │
│  │  │ Month          │ Current     │ New         │ Change              ││ │
│  │  ├───────────────────────────────────────────────────────────────────┤│ │
│  │  │ Dec 2026       │ 2,000 PLN   │ 2,000 PLN   │ unchanged          ││ │
│  │  │ ─────────────────────────────────────────────────────────────────││ │
│  │  │ Jan 2027 ✨    │ 2,000 PLN   │ 2,200 PLN   │ +200 PLN           ││ │
│  │  │ Feb 2027       │ 2,000 PLN   │ 2,200 PLN   │ +200 PLN           ││ │
│  │  │ Mar 2027       │ 2,000 PLN   │ 2,200 PLN   │ +200 PLN           ││ │
│  │  │ Apr 2027       │ 2,000 PLN   │ 2,200 PLN   │ +200 PLN           ││ │
│  │  │ May 2027       │ 2,000 PLN   │ 2,200 PLN   │ +200 PLN           ││ │
│  │  │ Jun 2027       │ 2,000 PLN   │ 2,200 PLN   │ +200 PLN           ││ │
│  │  └───────────────────────────────────────────────────────────────────┘│ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  ⚠️ Note: Transactions before January 2027 will not be affected.     │ │
│  │                                                                        │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  [Back]                                           [Confirm Changes]    │ │
│  │                                                        ▲               │ │
│  │                                                        │               │ │
│  │                                              primary button, green     │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  STEP 4: SUCCESS CONFIRMATION                                                │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                        │ │
│  │                            ✓                                           │ │
│  │                                                                        │ │
│  │                   Amount Change Scheduled                              │ │
│  │                                                                        │ │
│  │         Rule "Czynsz" will change to 2,200 PLN                        │ │
│  │                 starting January 2027.                                 │ │
│  │                                                                        │ │
│  │              6 transactions have been updated.                         │ │
│  │                                                                        │ │
│  │                         [Done]                                         │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Proactive Flow - Multiple Scheduled Changes

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     UI: Multiple Scheduled Changes                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Kredyt z wieloma zmianami oprocentowania                         │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Kredyt hipoteczny                                         [Edit] [⋮]   │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │  Current Amount: 1,850 PLN                                            │ │
│  │  Pattern: Monthly, 5th                                                │ │
│  │                                                                        │ │
│  │  ┌──────────────────────────────────────────────────────────────────┐ │ │
│  │  │ 📅 Scheduled Changes                                              │ │ │
│  │  │                                                                    │ │ │
│  │  │  ┌────────────────────────────────────────────────────────────┐  │ │ │
│  │  │  │ Jul 2027          1,920 PLN    +70 PLN    [Edit] [Cancel]  │  │ │ │
│  │  │  │ Zmiana oprocentowania Q3                                    │  │ │ │
│  │  │  └────────────────────────────────────────────────────────────┘  │ │ │
│  │  │                                                                    │ │ │
│  │  │  ┌────────────────────────────────────────────────────────────┐  │ │ │
│  │  │  │ Jan 2028          1,990 PLN    +70 PLN    [Edit] [Cancel]  │  │ │ │
│  │  │  │ Zmiana oprocentowania Q1 2028                               │  │ │ │
│  │  │  └────────────────────────────────────────────────────────────┘  │ │ │
│  │  │                                                                    │ │ │
│  │  │  [+ Add Another Change]                                           │ │ │
│  │  └──────────────────────────────────────────────────────────────────┘ │ │
│  │                                                                        │ │
│  │  ┌──────────────────────────────────────────────────────────────────┐ │ │
│  │  │ 📊 Amount Timeline                                                │ │ │
│  │  │                                                                    │ │ │
│  │  │  1,990 ─────────────────────────────────────────────────● Jan 28 │ │ │
│  │  │                                                        ╱         │ │ │
│  │  │  1,920 ───────────────────────────────● Jul 27 ───────╱          │ │ │
│  │  │                                       ╱                           │ │ │
│  │  │  1,850 ● Now ────────────────────────╱                            │ │ │
│  │  │        │                                                          │ │ │
│  │  │        └──────────────────────────────────────────────────────▶  │ │ │
│  │  │       2026           2027                    2028                 │ │ │
│  │  └──────────────────────────────────────────────────────────────────┘ │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.3 Reactive Flow - Mismatch Detection Dialog

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     UI: Mismatch Detection Dialog                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Bank transaction z inną kwotą niż EXPECTED                       │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ ⚠️ Amount Mismatch Detected                                       [×]  │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │  We found a potential match, but the amounts differ:                   │ │
│  │                                                                        │ │
│  │  ┌────────────────────────────────────────────────────────────────┐   │ │
│  │  │                                                                │   │ │
│  │  │   Bank Transaction              Expected (from rule)          │   │ │
│  │  │   ──────────────────           ─────────────────────          │   │ │
│  │  │                                                                │   │ │
│  │  │   💳 2,200.00 PLN               📋 2,000.00 PLN               │   │ │
│  │  │   "CZYNSZ STYCZEŃ 2027"         "Czynsz" (rule)               │   │ │
│  │  │   Jan 10, 2027                  Due: Jan 10, 2027             │   │ │
│  │  │                                                                │   │ │
│  │  │                    ┌─────────────────┐                         │   │ │
│  │  │                    │  Difference:    │                         │   │ │
│  │  │                    │  +200.00 PLN    │                         │   │ │
│  │  │                    │  (+10.0%)       │                         │   │ │
│  │  │                    └─────────────────┘                         │   │ │
│  │  │                                                                │   │ │
│  │  └────────────────────────────────────────────────────────────────┘   │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  What would you like to do?                                            │ │
│  │                                                                        │ │
│  │  ┌────────────────────────────────────────────────────────────────┐   │ │
│  │  │ ○ Match anyway (one-time difference)                           │   │ │
│  │  │   This month's payment was different. Keep rule at 2,000 PLN.  │   │ │
│  │  │   Future expected amounts will remain 2,000 PLN.               │   │ │
│  │  └────────────────────────────────────────────────────────────────┘   │ │
│  │                                                                        │ │
│  │  ┌────────────────────────────────────────────────────────────────┐   │ │
│  │  │ ● Update rule permanently                              ← Best  │   │ │
│  │  │   Change rule to 2,200 PLN from now on.                        │   │ │
│  │  │                                                                │   │ │
│  │  │   ☑ Update 6 future expected transactions                     │   │ │
│  │  │     Feb 2027 - Jul 2027 will change to 2,200 PLN              │   │ │
│  │  └────────────────────────────────────────────────────────────────┘   │ │
│  │                                                                        │ │
│  │  ┌────────────────────────────────────────────────────────────────┐   │ │
│  │  │ ○ This is not related to "Czynsz"                              │   │ │
│  │  │   Create a separate transaction. The expected "Czynsz"         │   │ │
│  │  │   transaction will remain for the actual payment.              │   │ │
│  │  └────────────────────────────────────────────────────────────────┘   │ │
│  │                                                                        │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  [Cancel]                                              [Apply Choice]  │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.4 Reactive Flow - Update Rule Preview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     UI: Update Rule Preview (from Mismatch)                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Po wybraniu "Update rule permanently":                                      │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Confirm Rule Update                                               [×]  │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │  You're about to update rule "Czynsz":                                │ │
│  │                                                                        │ │
│  │  ┌──────────────────────────────────────────────────────────────────┐ │ │
│  │  │                                                                  │ │ │
│  │  │   2,000 PLN  ────────────────────▶  2,200 PLN                   │ │ │
│  │  │     (old)                              (new)                     │ │ │
│  │  │                       +10%                                       │ │ │
│  │  │                                                                  │ │ │
│  │  └──────────────────────────────────────────────────────────────────┘ │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  📊 What will happen:                                                  │ │
│  │                                                                        │ │
│  │  ✓ Match bank transaction (2,200 PLN) to expected "Czynsz"           │ │
│  │  ✓ Update rule amount: 2,000 → 2,200 PLN                             │ │
│  │  ✓ Update 6 future transactions:                                     │ │
│  │                                                                        │ │
│  │    • Feb 10, 2027: 2,000 → 2,200 PLN                                 │ │
│  │    • Mar 10, 2027: 2,000 → 2,200 PLN                                 │ │
│  │    • Apr 10, 2027: 2,000 → 2,200 PLN                                 │ │
│  │    • May 10, 2027: 2,000 → 2,200 PLN                                 │ │
│  │    • Jun 10, 2027: 2,000 → 2,200 PLN                                 │ │
│  │    • Jul 10, 2027: 2,000 → 2,200 PLN                                 │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  💰 Financial Impact:                                                  │ │
│  │                                                                        │ │
│  │    Monthly increase:  +200 PLN                                        │ │
│  │    6-month impact:    +1,200 PLN                                      │ │
│  │                                                                        │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  [Back]                                      [Confirm & Update Rule]   │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.5 Reactive Flow - Estimate Tolerance Auto-Match

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     UI: Auto-Match with Tolerance                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Rule z amountIsEstimate=true (np. rachunek za prąd)              │
│                                                                              │
│  Przypadek A: Kwota w tolerancji (20%) - AUTO-MATCH                         │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Rule: "Prąd" = ~150 PLN (estimate, ±20%)                                   │
│  Bank transaction: 167 PLN (+11.3%)                                         │
│                                                                              │
│  → System automatycznie matchuje (bez pytania usera)                        │
│  → Toast notification:                                                       │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ ✓ Matched "TAURON" (167 PLN) to "Prąd" (expected ~150 PLN)            │ │
│  │   Amount within estimate tolerance (+11%)                    [Undo]   │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Przypadek B: Kwota POZA tolerancją - MISMATCH DIALOG                       │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Rule: "Prąd" = ~150 PLN (estimate, ±20%)                                   │
│  Bank transaction: 250 PLN (+67%)                                           │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ ⚠️ Significant Amount Difference                                  [×]  │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │  Bank transaction "TAURON" differs significantly from expected:        │ │
│  │                                                                        │ │
│  │  💳 250.00 PLN  vs  📋 ~150.00 PLN (estimate)                         │ │
│  │                                                                        │ │
│  │  Difference: +100.00 PLN (+67%)                                       │ │
│  │  ⚠️ This exceeds the 20% estimate tolerance.                          │ │
│  │                                                                        │ │
│  │  ────────────────────────────────────────────────────────────────────  │ │
│  │                                                                        │ │
│  │  ○ Match anyway (higher than usual bill)                              │ │
│  │                                                                        │ │
│  │  ○ Update estimate to ~250 PLN                                        │ │
│  │    ☐ Update future expected amounts                                   │ │
│  │                                                                        │ │
│  │  ○ Increase tolerance to 70%                                          │ │
│  │    Future bills up to ~255 PLN will auto-match                        │ │
│  │                                                                        │ │
│  │  ○ This is not my electricity bill                                    │ │
│  │                                                                        │ │
│  │  [Cancel]                                              [Apply Choice]  │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.6 Amount History View

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     UI: Amount History                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Czynsz - Amount History                                           [×]  │ │
│  ├────────────────────────────────────────────────────────────────────────┤ │
│  │                                                                        │ │
│  │  Current Amount: 2,200 PLN                                            │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  📈 Amount Timeline                                                    │ │
│  │                                                                        │ │
│  │  2,200 ─────────────────────────────────────────────────● Now         │ │
│  │                                                        ╱              │ │
│  │  2,000 ────────────────────────● Jan 26 ──────────────╱               │ │
│  │                               ╱                                        │ │
│  │  1,800 ● Jan 25 ─────────────╱                                         │ │
│  │        │                                                               │ │
│  │        └────────────────────────────────────────────────────────────▶ │ │
│  │       2025              2026                   2027                    │ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  📋 Change History                                                     │ │
│  │                                                                        │ │
│  │  ┌───────────────────────────────────────────────────────────────────┐│ │
│  │  │ Jan 2027        2,200 PLN    +200 PLN    from mismatch            ││ │
│  │  │                              (+10%)      "Bank transaction differ" ││ │
│  │  ├───────────────────────────────────────────────────────────────────┤│ │
│  │  │ Jan 2026        2,000 PLN    +200 PLN    scheduled change         ││ │
│  │  │                              (+11%)      "Podwyżka 2026"          ││ │
│  │  ├───────────────────────────────────────────────────────────────────┤│ │
│  │  │ Jan 2025        1,800 PLN    —           initial amount           ││ │
│  │  │                                          Rule created              ││ │
│  │  └───────────────────────────────────────────────────────────────────┘│ │
│  │                                                                        │ │
│  │  ════════════════════════════════════════════════════════════════════ │ │
│  │                                                                        │ │
│  │  📊 Statistics                                                         │ │
│  │                                                                        │ │
│  │  Total increase since creation: +400 PLN (+22%)                       │ │
│  │  Average annual increase: +200 PLN (11%)                              │ │
│  │  Changes: 2 in 2 years                                                │ │
│  │                                                                        │ │
│  │                                                        [Close]         │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Business Analysis

### 7.1 Use Case Matrix

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     USE CASE BUSINESS MATRIX                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Expense Type     │ Amount     │ Predictable │ Recommended  │ Tolerance    │
│                   │ Variability│ Changes?    │ Flow         │              │
│  ─────────────────┼────────────┼─────────────┼──────────────┼───────────── │
│                   │            │             │              │              │
│  Czynsz           │ Fixed      │ Yes (annual)│ Proactive    │ 0%           │
│                   │            │             │              │              │
│  Kredyt           │ Fixed*     │ Yes (semi)  │ Proactive    │ 0%           │
│                   │ *can change│             │ (multiple)   │              │
│                   │            │             │              │              │
│  Netflix          │ Fixed      │ No          │ Reactive     │ 0%           │
│                   │            │             │              │              │
│  Prąd             │ Variable   │ No          │ Reactive     │ 20-30%       │
│                   │            │             │ (tolerance)  │              │
│                   │            │             │              │              │
│  Gaz              │ Variable   │ Seasonal    │ Both         │ 30-50%       │
│                   │            │             │              │              │
│  Telefon          │ Fixed*     │ Sometimes   │ Reactive     │ 5%           │
│                   │ *usually   │             │              │              │
│                   │            │             │              │              │
│  Ubezpieczenie    │ Fixed      │ Yes (annual)│ Proactive    │ 0%           │
│                   │            │             │              │              │
│  Wynagrodzenie    │ Fixed*     │ Sometimes   │ Both         │ 0%           │
│                   │            │             │              │              │
│  Freelance income │ Variable   │ No          │ N/A          │ High         │
│                   │            │             │              │              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Business Value

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     BUSINESS VALUE ANALYSIS                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PROBLEM SOLVED                          BUSINESS VALUE                      │
│  ══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  1. Manual transaction updates           → Reduced user friction            │
│     User had to edit each EXPECTED         Time saved: ~5 min per change   │
│     transaction individually               Annual savings: 30+ min/user    │
│                                                                              │
│  2. Inaccurate forecasts                 → Better financial planning       │
│     Old amounts in future months           Forecasts reflect reality       │
│     gave wrong cash flow predictions       User trust in system +50%       │
│                                                                              │
│  3. Missed rule updates                  → Automatic detection             │
│     User forgot to update rule             System catches mismatches       │
│     after price change                     No more "stale" rules           │
│                                                                              │
│  4. No historical tracking               → Full audit trail                │
│     User didn't know original              "How much did I pay 2 years    │
│     amounts                                ago?" - answerable              │
│                                                                              │
│  5. Variable bills frustration           → Tolerance-based matching        │
│     Utility bills never matched            Auto-match within tolerance     │
│     exactly                                Less manual confirmation        │
│                                                                              │
│  ══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  KEY METRICS TO TRACK:                                                       │
│                                                                              │
│  • % of transactions auto-matched (target: >80%)                            │
│  • % of mismatches resolved with "Update rule" (vs other options)          │
│  • Average time to resolve mismatch (<30 seconds)                          │
│  • Number of scheduled changes created per user                             │
│  • User satisfaction with amount change flow (NPS)                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.3 User Personas

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     USER PERSONAS                                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PERSONA 1: "Planista" (Proactive User)                                     │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Profile:                                                                    │
│  • Zawsze wie o nadchodzących zmianach                                      │
│  • Czyta pisma od zarządcy, banku                                           │
│  • Lubi mieć wszystko zaplanowane                                           │
│                                                                              │
│  Behavior:                                                                   │
│  • Dostaje pismo o podwyżce → od razu scheduluje zmianę w systemie         │
│  • Korzysta głównie z Proactive Flow                                        │
│  • Preview jest dla niego KLUCZOWY (chce widzieć wpływ)                    │
│                                                                              │
│  Needs:                                                                      │
│  • Łatwy sposób planowania zmian                                            │
│  • Wizualizacja wpływu na budżet                                            │
│  • Możliwość planowania wielu zmian (kredyt)                                │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  PERSONA 2: "Reaktywny" (Reactive User)                                     │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Profile:                                                                    │
│  • Dowiaduje się o zmianach gdy widzi rachunek                              │
│  • Nie czyta drobnego druczku                                               │
│  • "Zaskoczony" podwyżkami                                                  │
│                                                                              │
│  Behavior:                                                                   │
│  • Bank transaction różni się od expected → mismatch dialog                │
│  • Korzysta głównie z Reactive Flow                                         │
│  • Potrzebuje jasnych opcji do wyboru                                       │
│                                                                              │
│  Needs:                                                                      │
│  • System wykrywa i informuje o zmianach                                    │
│  • Proste opcje: "match anyway" vs "update rule"                           │
│  • Automatyczna propagacja do przyszłych transakcji                        │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  PERSONA 3: "Estymator" (Variable Bills User)                               │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Profile:                                                                    │
│  • Ma rachunki o zmiennej wysokości (prąd, gaz)                            │
│  • Wie że kwota się zmienia co miesiąc                                      │
│  • Frustruje go "mismatch" dla każdego rachunku                            │
│                                                                              │
│  Behavior:                                                                   │
│  • Ustawia amountIsEstimate=true i tolerance                               │
│  • Oczekuje auto-match w zakresie tolerancji                               │
│  • Alerty tylko gdy coś "dziwnego"                                         │
│                                                                              │
│  Needs:                                                                      │
│  • Tolerance-based matching                                                  │
│  • "Match anyway" bez pytania w zakresie                                    │
│  • Alert gdy znacząco poza zakresem                                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Edge Cases

### 8.1 Edge Cases Matrix

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     EDGE CASES                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  EC-1: Scheduled change effective date already passed                       │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario:                                                                   │
│  • User scheduluje zmianę na Jan 2027                                       │
│  • Przed Jan 2027 system był offline / user nie logował się               │
│  • Jest już Feb 2027, zmiana nie została applied                           │
│                                                                              │
│  Solution:                                                                   │
│  • Month rollover job sprawdza pending scheduled changes                    │
│  • Apply retroactively z alertem dla usera                                  │
│  • "Scheduled change from Jan 2027 was applied (delayed)"                  │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  EC-2: Multiple scheduled changes for same month                            │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario:                                                                   │
│  • User scheduluje: Jan 2027 → 2,200 PLN                                   │
│  • User próbuje dodać: Jan 2027 → 2,300 PLN                                │
│                                                                              │
│  Solution:                                                                   │
│  • Block: "Already have change for Jan 2027. Edit or cancel first."       │
│  • Alternative: Replace existing (with confirmation)                        │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  EC-3: Scheduled change + immediate edit conflict                           │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario:                                                                   │
│  • User ma scheduled change: Jan 2027 → 2,200 PLN                          │
│  • User edits rule amount now: 2,000 → 2,100 PLN                           │
│  • Co ze scheduled change?                                                  │
│                                                                              │
│  Solution:                                                                   │
│  • Show warning: "You have a scheduled change for Jan 2027"                │
│  • Options:                                                                 │
│    ○ Apply 2,100 now, keep scheduled 2,200 for Jan                        │
│    ○ Apply 2,100 now, update scheduled to 2,200 (+100 from new base)      │
│    ○ Apply 2,100 now, cancel scheduled change                              │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  EC-4: Mismatch detection when rule already has scheduled change           │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario:                                                                   │
│  • Rule: 2,000 PLN, scheduled: Jan 2027 → 2,200 PLN                        │
│  • Bank transaction arrives: Dec 2026, 2,150 PLN                           │
│  • Mismatch detected (expected 2,000)                                       │
│                                                                              │
│  Solution:                                                                   │
│  • Show mismatch dialog with context:                                       │
│    "Note: You have 2,200 PLN scheduled for Jan 2027"                       │
│  • Options:                                                                 │
│    ○ Match anyway (Dec was exception)                                      │
│    ○ Update rule to 2,150 PLN now (affects scheduled too?)                │
│    ○ This is different transaction                                         │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  EC-5: Rule deleted with scheduled changes                                  │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario:                                                                   │
│  • Rule has scheduled changes                                               │
│  • User deletes rule                                                        │
│                                                                              │
│  Solution:                                                                   │
│  • Show in delete confirmation:                                             │
│    "This rule has 2 scheduled amount changes that will be cancelled"      │
│  • Delete cancels all scheduled changes                                    │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  EC-6: Mismatch with very small difference (rounding)                       │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario:                                                                   │
│  • Expected: 29.99 PLN                                                      │
│  • Bank: 30.00 PLN (1 grosz różnicy)                                       │
│                                                                              │
│  Solution:                                                                   │
│  • Auto-match for differences < 1% (or < 1 PLN)                            │
│  • No mismatch dialog for rounding errors                                  │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  EC-7: Currency change (theoretical)                                        │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario:                                                                   │
│  • Rule: 2,000 PLN                                                          │
│  • User tries to schedule change in EUR                                    │
│                                                                              │
│  Solution:                                                                   │
│  • Block: Scheduled change must be same currency as rule                   │
│  • (Currency change requires new rule)                                     │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  EC-8: EXPECTED transaction already CONFIRMED                               │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  Scenario:                                                                   │
│  • User confirms Jan transaction manually (2,000 PLN)                       │
│  • Later, user schedules change from Jan (2,200 PLN)                       │
│                                                                              │
│  Solution:                                                                   │
│  • Only update EXPECTED (not CONFIRMED) transactions                       │
│  • Preview shows: "1 transaction already confirmed, will not change"      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 Validation Rules

```java
public class AmountChangeValidationRules {

    // Proactive Flow Validations
    public static final List<ValidationRule<ScheduleAmountChangeCommand>> SCHEDULE_RULES = List.of(
        // 1. Effective date must be in future
        cmd -> cmd.effectiveFrom().isAfter(YearMonth.now())
            ? ValidationResult.valid()
            : ValidationResult.invalid("Effective date must be in future"),

        // 2. Amount must be positive
        cmd -> cmd.newAmount().isPositive()
            ? ValidationResult.valid()
            : ValidationResult.invalid("Amount must be positive"),

        // 3. Amount must be different from current
        (cmd, rule) -> !cmd.newAmount().equals(rule.getCurrentAmount())
            ? ValidationResult.valid()
            : ValidationResult.invalid("New amount is same as current"),

        // 4. No conflicting scheduled change
        (cmd, rule) -> rule.getScheduledChanges().stream()
            .filter(sc -> sc.status() == ScheduledChangeStatus.PENDING)
            .noneMatch(sc -> sc.effectiveFrom().equals(cmd.effectiveFrom()))
            ? ValidationResult.valid()
            : ValidationResult.invalid("Already have scheduled change for " + cmd.effectiveFrom()),

        // 5. Currency must match
        (cmd, rule) -> cmd.newAmount().getCurrency().equals(rule.getCurrentAmount().getCurrency())
            ? ValidationResult.valid()
            : ValidationResult.invalid("Currency must match rule currency"),

        // 6. Max scheduled changes limit
        (cmd, rule) -> rule.getScheduledChanges().stream()
            .filter(sc -> sc.status() == ScheduledChangeStatus.PENDING)
            .count() < 12
            ? ValidationResult.valid()
            : ValidationResult.invalid("Maximum 12 scheduled changes allowed")
    );

    // Reactive Flow Validations
    public static final List<ValidationRule<MismatchResolutionRequest>> MISMATCH_RULES = List.of(
        // 1. Expected transaction must exist and be EXPECTED status
        req -> /* check */ ValidationResult.valid(),

        // 2. Rule must exist and be active
        req -> /* check */ ValidationResult.valid(),

        // 3. If UPDATE_RULE, new amount must be different
        req -> req.resolution() != MismatchResolution.UPDATE_RULE ||
            !req.actualAmount().equals(req.expectedAmount())
            ? ValidationResult.valid()
            : ValidationResult.invalid("Amount must be different to update rule")
    );
}
```

---

## 9. Implementation Plan

### 9.1 Phases

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     IMPLEMENTATION PHASES                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PHASE 1: Domain Model & Basic Proactive Flow (Week 1-2)                    │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ Extend RecurringRule with:                                               │
│    □ scheduledChanges: List<ScheduledAmountChange>                          │
│    □ amountHistory: List<AmountHistoryEntry>                                │
│  □ Implement scheduleAmountChange() domain method                           │
│  □ Implement cancelScheduledChange() domain method                          │
│  □ Implement getEffectiveAmountForMonth() method                            │
│  □ Add AmountChangeScheduledEvent                                           │
│  □ MongoDB schema migration                                                  │
│                                                                              │
│  PHASE 2: Proactive Flow API & Basic UI (Week 2-3)                          │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ API endpoints:                                                            │
│    □ POST /rules/{id}/scheduled-changes                                     │
│    □ POST /rules/{id}/scheduled-changes/preview                             │
│    □ DELETE /rules/{id}/scheduled-changes/{changeId}                        │
│    □ GET /rules/{id}/amount-history                                         │
│  □ Command handlers:                                                         │
│    □ ScheduleAmountChangeCommandHandler                                     │
│    □ CancelScheduledChangeCommandHandler                                    │
│  □ UI:                                                                       │
│    □ "Schedule Amount Change" button on rule page                           │
│    □ Schedule dialog with form                                              │
│    □ Preview dialog with impact summary                                     │
│                                                                              │
│  PHASE 3: Transaction Propagation (Week 3-4)                                │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ TransactionPropagationService                                            │
│  □ Update future EXPECTED on scheduled change confirm                       │
│  □ Revert EXPECTED on scheduled change cancel                               │
│  □ Handle already CONFIRMED transactions                                    │
│  □ Integration with CashFlow API                                            │
│                                                                              │
│  PHASE 4: Reactive Flow - Matching (Week 4-5)                               │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ TransactionMatchingService                                               │
│    □ findMatchCandidates()                                                  │
│    □ scoreMatch() with amount comparison                                    │
│    □ Tolerance-based matching for estimates                                 │
│  □ MatchResult types (Perfect, Tolerance, Mismatch, NoMatch)               │
│  □ Integration with bank data ingestion flow                                │
│                                                                              │
│  PHASE 5: Reactive Flow - Mismatch Resolution (Week 5-6)                    │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ MismatchResolutionService                                                │
│    □ resolveMatchAnyway()                                                   │
│    □ resolveUpdateRule() with propagation                                   │
│    □ resolveNotRelated()                                                    │
│  □ API endpoint: POST /resolve-mismatch                                     │
│  □ UI: Mismatch detection dialog                                            │
│  □ UI: Update rule preview (from mismatch)                                  │
│                                                                              │
│  PHASE 6: Polish & Edge Cases (Week 6-7)                                    │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  □ Amount history UI                                                         │
│  □ Multiple scheduled changes UI                                            │
│  □ Timeline visualization                                                    │
│  □ Edge case handling                                                        │
│  □ Comprehensive testing                                                     │
│  □ Documentation                                                             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.2 Test Scenarios

```java
class AmountChangesIntegrationTest {

    // Proactive Flow Tests
    @Test
    void shouldScheduleAmountChangeAndUpdateFutureTransactions() {}

    @Test
    void shouldPreviewImpactOfScheduledChange() {}

    @Test
    void shouldCancelScheduledChangeAndRevertTransactions() {}

    @Test
    void shouldApplyScheduledChangeOnMonthRollover() {}

    @Test
    void shouldHandleMultipleScheduledChanges() {}

    @Test
    void shouldRejectScheduledChangeForPastMonth() {}

    @Test
    void shouldRejectConflictingScheduledChange() {}

    // Reactive Flow Tests
    @Test
    void shouldDetectMismatchAndShowOptions() {}

    @Test
    void shouldMatchAnywayWithoutUpdatingRule() {}

    @Test
    void shouldUpdateRuleAndPropagateToFuture() {}

    @Test
    void shouldCreateSeparateTransactionWhenNotRelated() {}

    @Test
    void shouldAutoMatchWithinTolerance() {}

    @Test
    void shouldShowMismatchDialogOutsideTolerance() {}

    // Edge Cases
    @Test
    void shouldHandleScheduledChangeWithExistingMismatch() {}

    @Test
    void shouldNotUpdateConfirmedTransactions() {}

    @Test
    void shouldHandleRoundingDifferencesAsExact() {}

    // Amount History
    @Test
    void shouldRecordAmountChangesInHistory() {}

    @Test
    void shouldReturnCompleteAmountTimeline() {}
}
```

---

## Appendix: Quick Reference

### A.1 Commands Summary

| Command | Description | Proactive/Reactive |
|---------|-------------|-------------------|
| `ScheduleAmountChangeCommand` | Plan future amount change | Proactive |
| `CancelScheduledChangeCommand` | Cancel pending change | Proactive |
| `ResolveMismatchCommand` | Handle detected mismatch | Reactive |
| `ApplyScheduledChangesCommand` | Apply due changes (month rollover) | System |

### A.2 Events Summary

| Event | Trigger | Listeners |
|-------|---------|-----------|
| `AmountChangeScheduledEvent` | User schedules change | Transaction propagation |
| `ScheduledChangeCancelledEvent` | User cancels change | Revert transactions |
| `AmountChangedEvent` | Amount actually changes | History, propagation |
| `MismatchDetectedEvent` | Bank tx doesn't match | UI notification |
| `MismatchResolvedEvent` | User resolves mismatch | Logging, metrics |

### A.3 UI States

| State | Description | User Action |
|-------|-------------|-------------|
| `NO_SCHEDULED` | Rule has no scheduled changes | Can add |
| `HAS_PENDING` | Scheduled changes exist | Can edit/cancel |
| `MISMATCH_DETECTED` | Bank tx differs | Must resolve |
| `AUTO_MATCHED` | Within tolerance | Can undo |
