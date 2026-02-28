# Recurring Rules - Implementation Status Analysis

**Data utworzenia:** 2026-02-28
**Status:** Analiza implementacji
**Autor:** Claude Code
**Powiązane dokumenty:**
- `2026-02-14-recurring-rule-engine-design.md` (główny design)
- `2026-02-25-recurring-rules-amount-changes-design.md` (zmiany kwot)
- `2026-02-25-recurring-rules-edge-cases-analysis.md` (edge cases)
- `docs/RECURRING_RULES_API_GUIDE.md` (API documentation)

---

## Spis treści

1. [Podsumowanie](#1-podsumowanie)
2. [Zaimplementowane funkcjonalności](#2-zaimplementowane-funkcjonalności)
3. [Brakujące funkcjonalności](#3-brakujące-funkcjonalności)
4. [Brakujące eventy CashFlow](#4-brakujące-eventy-cashflow)
5. [Brakujące testy](#5-brakujące-testy)
6. [Rekomendacje kolejnych kroków](#6-rekomendacje-kolejnych-kroków)

---

## 1. Podsumowanie

| Kategoria | Zaimplementowane | Brakuje |
|-----------|------------------|---------|
| **Core CRUD** | 100% | 0% |
| **Basic Patterns** | 100% (4/4) | 3 dodatkowe |
| **Seasonal Rules** | 0% | 100% |
| **Error Handling** | ~60% | ~40% |
| **Event Handling** | ~50% | ~50% |
| **Edge Cases** | ~30% | ~70% |
| **AI Features** | 0% | 100% (out of scope MVP) |

**Obecna implementacja pokrywa ~80% MVP scope** zdefiniowanego w design doc.

---

## 2. Zaimplementowane funkcjonalności

### 2.1 Core Features (MVP Complete)

| Funkcjonalność | Status | Implementacja |
|----------------|--------|---------------|
| **CRUD operations** | ✅ | `RecurringRulesController.java` - POST/GET/PUT/DELETE |
| **Patterns: DAILY** | ✅ | `DailyPattern.java` - `intervalDays` |
| **Patterns: WEEKLY** | ✅ | `WeeklyPattern.java` - `dayOfWeek`, `intervalWeeks` |
| **Patterns: MONTHLY** | ✅ | `MonthlyPattern.java` - `dayOfMonth`, `intervalMonths`, `adjustForMonthEnd` |
| **Patterns: YEARLY** | ✅ | `YearlyPattern.java` - `month`, `dayOfMonth` |
| **Pause/Resume** | ✅ | `PauseInfo`, `pause()`, `resume()` methods |
| **Delete (soft)** | ✅ | `RuleStatus.DELETED` |
| **Auto-generation CashChanges** | ✅ | `RecurringRuleService` + `CashFlowHttpClient` |
| **Regenerate endpoint** | ✅ | `POST /{ruleId}/regenerate` |
| **Category validation** | ✅ | Walidacja przy tworzeniu reguły |
| **AmountChange support** | ✅ | `AmountChange`, `addAmountChange()`, `calculateEffectiveAmount()` |
| **Event sourcing** | ✅ | `RecurringRuleEvent` sealed interface |
| **JWT authentication** | ✅ | Token w Authorization header |
| **Error handling** | ✅ | Exception handlers w kontrolerze |
| **Integration tests** | ✅ | `RecurringRulesHttpIntegrationTest` |
| **CashFlow Forecast integration** | ✅ | Events przetwarzane przez `CashFlowForecastProcessor` |

### 2.2 Pliki implementacji

```
src/main/java/com/multi/vidulum/recurring_rules/
├── domain/
│   ├── RecurringRule.java              # Aggregate
│   ├── RecurringRuleSnapshot.java      # Snapshot
│   ├── RecurringRuleId.java            # Value Object
│   ├── RecurrencePattern.java          # Sealed interface
│   ├── DailyPattern.java               # Daily pattern
│   ├── WeeklyPattern.java              # Weekly pattern
│   ├── MonthlyPattern.java             # Monthly pattern
│   ├── YearlyPattern.java              # Yearly pattern
│   ├── RuleStatus.java                 # ACTIVE, PAUSED, COMPLETED, DELETED
│   ├── PauseInfo.java                  # Pause information
│   ├── AmountChange.java               # Amount change record
│   ├── RuleExecution.java              # Execution tracking
│   └── RecurringRuleEvent.java         # Domain events
├── app/
│   ├── RecurringRulesController.java   # REST API
│   ├── RecurringRuleService.java       # Application service
│   ├── commands/                       # CQRS commands
│   ├── queries/                        # CQRS queries
│   └── dto/                            # DTOs
└── infrastructure/
    ├── RecurringRuleEntity.java        # MongoDB entity
    ├── RecurringRuleMongoRepository.java
    ├── RecurringRuleRepositoryAdapter.java
    └── CashFlowHttpClient.java         # HTTP client
```

---

## 3. Brakujące funkcjonalności

### 3.1 Priorytet WYSOKI (MVP v1.1)

| Funkcjonalność | Opis | Dokument źródłowy |
|----------------|------|-------------------|
| **activeMonths (Seasonal rules)** | Wsparcie dla reguł sezonowych np. przedszkole IX-VI, ogrzewanie X-IV | Design doc sekcja 5.2, Story 6-7 |
| **excludedDates** | Lista dat do pominięcia np. "skip 2026-05-10" | Design doc sekcja 6.1 |
| **maxOccurrences** | Limit wystąpień np. 24 raty kredytu, auto-ENDED po osiągnięciu | Design doc sekcja 6.1, Story 8 |
| **amountIsEstimate flag** | Flaga dla kwot przybliżonych (większa tolerancja przy future matching) | Design doc sekcja 6.1 |
| **PauseReason enum** | `MANUAL`, `CATEGORY_ARCHIVED`, `CASHFLOW_CLOSED`, `GENERATION_FAILED` | Edge cases sekcja 1.3 |
| **GenerationStatus tracking** | `IDLE`, `PENDING`, `IN_PROGRESS`, `FAILED`, `SUCCESS` | Edge cases sekcja 2.2 |
| **dayOfMonth = -1 (last day)** | Wsparcie dla "ostatni dzień miesiąca" | Design doc Story 9 |
| **lastGenerationError field** | Przechowywanie ostatniego błędu generacji | Edge cases sekcja 2.2 |
| **failedAttempts counter** | Licznik nieudanych prób generacji | Edge cases sekcja 2.2 |

### 3.2 Priorytet ŚREDNI (v1.2)

| Funkcjonalność | Opis | Dokument źródłowy |
|----------------|------|-------------------|
| **QUARTERLY pattern** | Co kwartał (25-tego pierwszego miesiąca kwartału) | Design doc sekcja 6.2 |
| **EveryNDays pattern** | Co N dni z opcjonalnym constraint na dzień tygodnia | Design doc sekcja 6.2 |
| **ONCE pattern** | Jednorazowa transakcja na określoną datę (np. wykup leasingu) | Design doc sekcja 6.2, Story 14 |
| **counterpartyName hint** | Hint dla future reconciliation | Design doc sekcja 6.1 |
| **counterpartyAccount hint** | Hint dla future reconciliation (98% accuracy) | Design doc sekcja 6.1 |
| **amountTolerance** | Tolerancja kwoty przy matching (±50 PLN) | Design doc sekcja 6.1 |
| **dateTolerance** | Tolerancja daty przy matching (±5 dni) | Design doc sekcja 6.1 |
| **Category archived handling** | Auto-pause reguł gdy kategoria zarchiwizowana | Edge cases sekcja 1 |
| **CashFlowClosedEvent handling** | Auto-pause wszystkich reguł dla zamkniętego CashFlow | Edge cases sekcja 3.2 |
| **CashFlowDeletedEvent handling** | Hard delete wszystkich reguł | Edge cases sekcja 3.1 |
| **Retry strategy (exponential backoff)** | 1s, 2s, 4s, 8s retry przy HTTP failures | Edge cases sekcja 2.1 |
| **Failed Generation Recovery Job** | Scheduled job (hourly) do retry failed generations | Edge cases sekcja 2.3 |
| **Unique index na duplikaty** | `(cashFlowId, sourceRuleId, dueDate)` - idempotency | Edge cases sekcja 5.2 |

### 3.3 Priorytet NISKI (Future phases)

| Funkcjonalność | Opis | Phase |
|----------------|------|-------|
| **Pattern detection (AI)** | Automatyczne wykrywanie wzorców z historii transakcji | Phase 4 |
| **Auto-matching z bank transactions** | Dopasowanie importowanych transakcji do expected | Phase 5 |
| **Split rules** | Jedna transakcja → wiele kategorii (np. wynagrodzenia per pracownik) | Phase 5 |
| **Shared rules (multi-user)** | Współdzielone reguły między użytkownikami | Future |
| **Import rules z CSV/JSON** | Bulk import reguł | Future |
| **Notifications (email/push)** | Powiadomienia o problemach z regułami | Future |
| **Outbox pattern** | Guaranteed delivery dla eventów (production-ready) | Future |
| **Notes field** | Notatki użytkownika do reguły | Future |

---

## 4. Brakujące eventy CashFlow

Moduł `recurring_rules` musi nasłuchiwać na następujące eventy z modułu `cashflow`:

```java
// CashFlow domain events that recurring_rules must handle:

sealed interface CashFlowEvent {
    // EXISTING - already implemented
    record MonthRolledOverEvent(...) implements CashFlowEvent {}

    // NEW - must add to CashFlow module
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

    // OPTIONAL - for category rename handling
    record CategoryRenamedEvent(
        CashFlowId cashFlowId,
        CategoryName oldName,
        CategoryName newName,
        ZonedDateTime renamedAt
    ) implements CashFlowEvent {}
}
```

### Event Handling Matrix

| CashFlow Event | recurring_rules Action | User Notification |
|----------------|------------------------|-------------------|
| `MonthRolledOverEvent` | Generate next month for active rules | None (silent) |
| `CategoryArchivedEvent` | Pause rules using this category | Yes - explain why paused |
| `CategoryUnarchivedEvent` | Suggest resume for paused rules | Yes - offer to resume |
| `CashFlowClosedEvent` | Pause all rules for this CashFlow | Yes - in close confirmation |
| `CashFlowDeletedEvent` | Delete all rules for this CashFlow | None - part of delete flow |
| `CategoryRenamedEvent` | Update categoryName in rules | None |

---

## 5. Brakujące testy

### 5.1 Edge Cases Tests

```java
class RecurringRulesEdgeCasesTest {

    // Category lifecycle
    @Test void shouldRejectRuleCreationWhenCategoryArchived() {}
    @Test void shouldRejectRuleCreationWhenCategoryDoesNotExist() {}
    @Test void shouldRejectRuleCreationWhenCurrencyMismatch() {}
    @Test void shouldAutoPauseRuleWhenCategoryArchived() {}

    // CashFlow lifecycle
    @Test void shouldAutoPauseAllRulesWhenCashFlowClosed() {}
    @Test void shouldDeleteAllRulesWhenCashFlowDeleted() {}

    // HTTP error handling
    @Test void shouldRetryGenerationOnTransientHttpError() {}
    @Test void shouldPauseRuleAfterMaxRetryAttemptsExceeded() {}
    @Test void shouldNotCreateDuplicateTransactionsOnRetry() {}
    @Test void shouldHandlePartialBatchSuccess() {}

    // Rule lifecycle
    @Test void shouldEndRuleWhenMaxOccurrencesReached() {}
    @Test void shouldEndRuleWhenEndDateReached() {}
    @Test void shouldSkipPausedRulesOnMonthRollover() {}
    @Test void shouldGenerateNextMonthOnMonthRollover() {}

    // Orphaned data
    @Test void shouldShowOrphanedTransactionInfoWhenRuleDeleted() {}
}
```

### 5.2 Seasonal Rules Tests

```java
class SeasonalRulesTest {

    @Test void shouldGenerateOnlyForActiveMonths() {}
    @Test void shouldSkipExcludedDates() {}
    @Test void shouldHandleLastDayOfMonth() {}
    @Test void shouldHandleFebruaryEdgeCases() {}
}
```

---

## 6. Rekomendacje kolejnych kroków

### 6.1 Immediate (v1.1)

1. **Seasonal rules support**
   - Dodaj `activeMonths: List<Month>` do `RecurringRule`
   - Dodaj `excludedDates: List<LocalDate>` do `RecurringRule`
   - Zmodyfikuj `generateOccurrences()` aby uwzględniać te pola

2. **maxOccurrences support**
   - Dodaj `maxOccurrences: Integer` do `RecurringRule`
   - Dodaj auto-COMPLETED gdy `generatedCount >= maxOccurrences`

3. **Last day of month**
   - Dodaj wsparcie dla `dayOfMonth = -1` w `MonthlyPattern`

4. **PauseReason tracking**
   - Dodaj `PauseReason` enum do `PauseInfo`
   - Rozszerz `pause()` method o reason

### 6.2 Short-term (v1.2)

1. **New patterns**
   - Implementuj `QuarterlyPattern`
   - Implementuj `EveryNDaysPattern`
   - Implementuj `OncePattern`

2. **CashFlow event handling**
   - Dodaj eventy do CashFlow module
   - Zaimplementuj event handlers w recurring_rules

3. **Error recovery**
   - Implementuj retry strategy z exponential backoff
   - Dodaj `FailedGenerationRecoveryJob`

### 6.3 Medium-term (v2.0)

1. **Matching hints**
   - Dodaj `counterpartyName`, `counterpartyAccount`
   - Dodaj `amountTolerance`, `dateTolerance`

2. **Reconciliation groundwork**
   - Przygotuj infrastrukturę dla Phase 5

---

## Appendix: File References

| File | Purpose |
|------|---------|
| `RecurringRule.java:56-103` | Factory method `create()` |
| `RecurringRule.java:160-190` | `update()` method |
| `RecurringRule.java:192-211` | `pause()` and `resume()` methods |
| `RecurringRule.java:324-338` | `generateOccurrences()` - needs seasonal support |
| `MonthlyPattern.java:26-41` | `nextOccurrenceFrom()` - needs last day support |
| `RecurringRulesController.java` | REST API endpoints |
| `RecurringRuleService.java` | Application service with business logic |
