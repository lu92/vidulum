# Recurring Rules - Implementation Status Analysis

**Data utworzenia:** 2026-02-28
**Ostatnia aktualizacja:** 2026-03-07
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
| **Advanced Features** | ~70% | ~30% |
| **Seasonal Rules** | 100% | 0% |
| **Error Handling** | ~60% | ~40% |
| **Event Handling** | ~50% | ~50% |
| **Edge Cases** | ~40% | ~60% |
| **AI Features** | 0% | 100% (out of scope MVP) |

**Obecna implementacja pokrywa ~90% MVP scope** zdefiniowanego w design doc.

### Ostatnie zmiany (VID-131, 2026-03-07)
- ✅ `activeMonths` - seasonal rules support
- ✅ `excludedDates` - skip specific dates
- ✅ `maxOccurrences` - limit occurrences with auto-COMPLETED
- ✅ `dayOfMonth = -1` - last day of month support
- ✅ `remainingOccurrences` - API response field

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

### 2.2 Advanced Features (VID-131, 2026-03-07)

| Funkcjonalność | Status | Implementacja | Opis |
|----------------|--------|---------------|------|
| **activeMonths** | ✅ | `RecurringRule.java:48,366` | Seasonal rules - np. przedszkole IX-VI |
| **excludedDates** | ✅ | `RecurringRule.java:49,372` | Pomijanie konkretnych dat |
| **maxOccurrences** | ✅ | `RecurringRule.java:47,361` | Limit wystąpień (np. 24 raty) |
| **Auto-COMPLETED** | ✅ | `RecurringRuleService.java:308-314` | Auto-zakończenie po osiągnięciu limitu |
| **dayOfMonth = -1** | ✅ | `MonthlyPattern.java:16,43-45` | Ostatni dzień miesiąca |
| **remainingOccurrences** | ✅ | `RecurringRuleResponse.java:38,44-48` | Pozostałe wykonania w API |

#### Szczegóły implementacji:

**activeMonths (seasonal rules):**
```java
// RecurringRule.java:366
if (activeMonths != null && !activeMonths.isEmpty() && !activeMonths.contains(current.getMonth())) {
    current = pattern.nextOccurrenceFrom(current.plusDays(1));
    continue;
}
```

**excludedDates:**
```java
// RecurringRule.java:372
if (excludedDates != null && excludedDates.contains(current)) {
    current = pattern.nextOccurrenceFrom(current.plusDays(1));
    continue;
}
```

**maxOccurrences + auto-COMPLETED:**
```java
// RecurringRule.java:389-391
public boolean shouldAutoComplete() {
    return maxOccurrences != null && generatedCashChangeIds.size() >= maxOccurrences;
}

// RecurringRuleService.java:308-314
if (rule.shouldAutoComplete()) {
    rule.complete("Reached maximum occurrences (" + rule.getMaxOccurrences().orElse(0) + ")", clock);
    ruleRepository.save(rule);
}
```

**dayOfMonth = -1 (last day of month):**
```java
// MonthlyPattern.java:16,43-45
public static final int LAST_DAY_OF_MONTH = -1;

if (isLastDayOfMonth()) {
    targetDay = fromDate.lengthOfMonth();
}
```

#### Nowe testy integracyjne (VID-131):

| Test | Opis |
|------|------|
| `shouldCreateRuleWithLastDayOfMonthAndGenerateCashChangesOnCorrectDates` | dayOfMonth=-1 generuje na ostatni dzień |
| `shouldHandleLastDayOfFebruaryCorrectly` | Obsługa lutego (28/29 dni) |
| `shouldAutoCompleteRuleWhenMaxOccurrencesReached` | Auto-COMPLETED po osiągnięciu limitu |
| `shouldNotAutoCompleteWhenBelowMaxOccurrences` | Brak auto-complete gdy poniżej limitu |
| `shouldShowRemainingOccurrencesInResponse` | remainingOccurrences w API |
| `shouldShowNullRemainingOccurrencesWhenNoMaxSet` | null gdy brak maxOccurrences |

### 2.3 Pliki implementacji

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

| Funkcjonalność | Opis | Status | Dokument źródłowy |
|----------------|------|--------|-------------------|
| **activeMonths (Seasonal rules)** | Wsparcie dla reguł sezonowych np. przedszkole IX-VI, ogrzewanie X-IV | ✅ VID-131 | Design doc sekcja 5.2, Story 6-7 |
| **excludedDates** | Lista dat do pominięcia np. "skip 2026-05-10" | ✅ VID-131 | Design doc sekcja 6.1 |
| **maxOccurrences** | Limit wystąpień np. 24 raty kredytu, auto-COMPLETED po osiągnięciu | ✅ VID-131 | Design doc sekcja 6.1, Story 8 |
| **dayOfMonth = -1 (last day)** | Wsparcie dla "ostatni dzień miesiąca" | ✅ VID-131 | Design doc Story 9 |
| **amountIsEstimate flag** | Flaga dla kwot przybliżonych (większa tolerancja przy future matching) | ❌ TODO | Design doc sekcja 6.1 |
| **PauseReason enum** | `MANUAL`, `CATEGORY_ARCHIVED`, `CASHFLOW_CLOSED`, `GENERATION_FAILED` | ❌ TODO | Edge cases sekcja 1.3 |
| **GenerationStatus tracking** | `IDLE`, `PENDING`, `IN_PROGRESS`, `FAILED`, `SUCCESS` | ❌ TODO | Edge cases sekcja 2.2 |
| **lastGenerationError field** | Przechowywanie ostatniego błędu generacji | ❌ TODO | Edge cases sekcja 2.2 |
| **failedAttempts counter** | Licznik nieudanych prób generacji | ❌ TODO | Edge cases sekcja 2.2 |

### 3.2 Priorytet ŚREDNI (v1.2)

| Funkcjonalność | Opis | Status | Dokument źródłowy |
|----------------|------|--------|-------------------|
| **QUARTERLY pattern** | Co kwartał (25-tego pierwszego miesiąca kwartału) | ❌ TODO | Design doc sekcja 6.2 |
| **EveryNDays pattern** | Co N dni z opcjonalnym constraint na dzień tygodnia | ❌ TODO | Design doc sekcja 6.2 |
| **ONCE pattern** | Jednorazowa transakcja na określoną datę (np. wykup leasingu) | ❌ TODO | Design doc sekcja 6.2, Story 14 |
| **counterpartyName hint** | Hint dla future reconciliation | ❌ TODO | Design doc sekcja 6.1 |
| **counterpartyAccount hint** | Hint dla future reconciliation (98% accuracy) | ❌ TODO | Design doc sekcja 6.1 |
| **amountTolerance** | Tolerancja kwoty przy matching (±50 PLN) | ❌ TODO | Design doc sekcja 6.1 |
| **dateTolerance** | Tolerancja daty przy matching (±5 dni) | ❌ TODO | Design doc sekcja 6.1 |
| **Category archived handling** | Auto-pause reguł gdy kategoria zarchiwizowana | ❌ TODO | Edge cases sekcja 1 |
| **CashFlowClosedEvent handling** | Auto-pause wszystkich reguł dla zamkniętego CashFlow | ❌ TODO | Edge cases sekcja 3.2 |
| **CashFlowDeletedEvent handling** | Hard delete wszystkich reguł | ❌ TODO | Edge cases sekcja 3.1 |
| **Retry strategy (exponential backoff)** | 1s, 2s, 4s, 8s retry przy HTTP failures | ❌ TODO | Edge cases sekcja 2.1 |
| **Failed Generation Recovery Job** | Scheduled job (hourly) do retry failed generations | ❌ TODO | Edge cases sekcja 2.3 |
| **Unique index na duplikaty** | `(cashFlowId, sourceRuleId, dueDate)` - idempotency | ❌ TODO | Edge cases sekcja 5.2 |

### 3.3 Priorytet NISKI (Future phases)

| Funkcjonalność | Opis | Status | Phase |
|----------------|------|--------|-------|
| **Pattern detection (AI)** | Automatyczne wykrywanie wzorców z historii transakcji | ❌ TODO | Phase 4 |
| **Auto-matching z bank transactions** | Dopasowanie importowanych transakcji do expected | ❌ TODO | Phase 5 |
| **Split rules** | Jedna transakcja → wiele kategorii (np. wynagrodzenia per pracownik) | ❌ TODO | Phase 5 |
| **Shared rules (multi-user)** | Współdzielone reguły między użytkownikami | ❌ TODO | Future |
| **Import rules z CSV/JSON** | Bulk import reguł | ❌ TODO | Future |
| **Notifications (email/push)** | Powiadomienia o problemach z regułami | ❌ TODO | Future |
| **Outbox pattern** | Guaranteed delivery dla eventów (production-ready) | ❌ TODO | Future |
| **Notes field** | Notatki użytkownika do reguły | ❌ TODO | Future |

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

### 5.2 Seasonal Rules Tests ✅ IMPLEMENTED (VID-131)

```java
class SeasonalRulesTest {

    @Test void shouldGenerateOnlyForActiveMonths() {}           // ✅ DONE
    @Test void shouldSkipExcludedDates() {}                     // ✅ DONE
    @Test void shouldHandleLastDayOfMonth() {}                  // ✅ DONE
    @Test void shouldHandleFebruaryEdgeCases() {}               // ✅ DONE
}
```

Testy zaimplementowane w `RecurringRulesHttpIntegrationTest.java`:
- `shouldCreateRuleWithLastDayOfMonthAndGenerateCashChangesOnCorrectDates`
- `shouldHandleLastDayOfFebruaryCorrectly`
- `shouldAutoCompleteRuleWhenMaxOccurrencesReached`
- `shouldNotAutoCompleteWhenBelowMaxOccurrences`
- `shouldShowRemainingOccurrencesInResponse`
- `shouldShowNullRemainingOccurrencesWhenNoMaxSet`

---

## 6. Rekomendacje kolejnych kroków

### 6.1 Immediate (v1.1) - ✅ COMPLETED (VID-131)

1. **Seasonal rules support** ✅ DONE
   - ~~Dodaj `activeMonths: List<Month>` do `RecurringRule`~~
   - ~~Dodaj `excludedDates: List<LocalDate>` do `RecurringRule`~~
   - ~~Zmodyfikuj `generateOccurrences()` aby uwzględniać te pola~~

2. **maxOccurrences support** ✅ DONE
   - ~~Dodaj `maxOccurrences: Integer` do `RecurringRule`~~
   - ~~Dodaj auto-COMPLETED gdy `generatedCount >= maxOccurrences`~~
   - Dodano `remainingOccurrences` w API response

3. **Last day of month** ✅ DONE
   - ~~Dodaj wsparcie dla `dayOfMonth = -1` w `MonthlyPattern`~~

4. **PauseReason tracking** ❌ TODO
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

| File | Purpose | Status |
|------|---------|--------|
| `RecurringRule.java:60-113` | Factory method `create()` with activeMonths, excludedDates, maxOccurrences | ✅ Updated |
| `RecurringRule.java:183-211` | `update()` method with all new fields | ✅ Updated |
| `RecurringRule.java:347-385` | `generateOccurrences()` with seasonal + excludedDates + maxOccurrences | ✅ Updated |
| `RecurringRule.java:389-403` | `shouldAutoComplete()` + `getRemainingOccurrences()` | ✅ New |
| `MonthlyPattern.java:16` | `LAST_DAY_OF_MONTH = -1` constant | ✅ New |
| `MonthlyPattern.java:30-32` | `isLastDayOfMonth()` helper | ✅ New |
| `MonthlyPattern.java:40-65` | `nextOccurrenceFrom()` with last day support | ✅ Updated |
| `MonthlyPattern.java:68-74` | `isValidForDate()` with last day support | ✅ Updated |
| `RecurringRuleService.java:308-314` | Auto-complete after maxOccurrences | ✅ New |
| `RecurringRuleResponse.java:38,44-48` | `remainingOccurrences` field | ✅ New |
| `RecurringRulesController.java` | REST API endpoints | ✅ |
| `RecurringRulesHttpIntegrationTest.java` | 6 new tests for VID-131 | ✅ New |
| `RecurringRulesHttpActor.java` | `createMonthlyRuleLastDayOfMonth()` helper | ✅ New |
