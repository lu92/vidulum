# Recurring Rules - Microservice Architecture Design

**Data utworzenia:** 2026-02-25
**Status:** Design - architektura modułu
**Autor:** Claude Code + User
**Powiązany dokument:** `2026-02-14-recurring-rule-engine-design.md` (funkcjonalny design)

---

## Spis treści

1. [Decyzja architektoniczna](#1-decyzja-architektoniczna)
2. [Analiza zależności](#2-analiza-zależności)
3. [Struktura pakietu](#3-struktura-pakietu)
4. [Domain Events](#4-domain-events)
5. [Komunikacja HTTP API](#5-komunikacja-http-api)
6. [Flow: Event → HTTP → CashFlow → Forecast](#6-flow-event--http--cashflow--forecast)
7. [CashFlow API Endpoints](#7-cashflow-api-endpoints)
8. [CashFlow HTTP Client](#8-cashflow-http-client)
9. [Month Rollover Integration](#9-month-rollover-integration)
10. [Shared Kernel - Common Types](#10-shared-kernel---common-types)
11. [Database Schema](#11-database-schema)
12. [Migration Plan](#12-migration-plan)
13. [Podsumowanie relacji](#13-podsumowanie-relacji)

---

## 1. Decyzja architektoniczna

### Rekomendacja: Osobny pakiet `recurring_rules`

Recurring Rules powinno być w **osobnym pakiecie** z możliwością łatwej ekstrakcji do mikroserwisu w przyszłości.

### Dlaczego osobny pakiet (a nie wewnątrz `cashflow`)?

| Aspekt | Wewnątrz `cashflow` | Osobny `recurring_rules` |
|--------|---------------------|--------------------------|
| **Bounded Context** | ❌ Miesza dwa konteksty | ✅ Osobny BC |
| **Aggregate** | ❌ CashFlow aggregate rośnie (już 26KB!) | ✅ RecurringRule to osobny aggregate |
| **Testowanie** | ❌ Testy zmieszane | ✅ Izolowane testy modułu |
| **Ekstrakcja do mikroserwisu** | ❌ Trudna | ✅ Copy-paste + zmiana HTTP client |
| **Team ownership** | ❌ Jeden zespół | ✅ Może być inny zespół |
| **Deployment** | ❌ Zawsze razem | ✅ Może być osobno |

### Kluczowa zasada: Komunikacja przez HTTP API

```
recurring_rules  ──── HTTP API ────▶  cashflow
                                          │
                                          │ Kafka events
                                          ▼
                                   cashflow_forecast_processor
```

**Recurring Rules NIE używa CommandGateway do CashFlow** - tylko HTTP API, jak zewnętrzny klient.

---

## 2. Analiza zależności

### Obecna architektura

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        CURRENT ARCHITECTURE                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   ┌─────────────┐     events      ┌──────────────────────────────┐      │
│   │  cashflow   │ ───────────────▶│ cashflow_forecast_processor  │      │
│   │  (domain)   │                 │   (event consumer, CQRS read)│      │
│   └──────┬──────┘                 └──────────────────────────────┘      │
│          │                                                               │
│          │ imports: CashFlowId, Type, CategoryName                      │
│          ▼                                                               │
│   ┌─────────────────────┐                                               │
│   │ bank_data_ingestion │                                               │
│   │   (CSV import)      │                                               │
│   └─────────────────────┘                                               │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Docelowa architektura z recurring_rules

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    PROPOSED: + recurring_rules                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   ┌─────────────┐              ┌──────────────────┐                     │
│   │  cashflow   │◀── HTTP ────│  recurring_rules │                      │
│   │  (domain)   │              │   (new module)   │                      │
│   └──────┬──────┘              └────────┬─────────┘                     │
│          │                              │                                │
│          │ Kafka                        │ listens to                     │
│          │ events                       │ MonthRolledOverEvent           │
│          ▼                              │                                │
│   ┌─────────────────────────────────────┴────────┐                      │
│   │         cashflow_forecast_processor           │                      │
│   │           (CQRS read model)                   │                      │
│   └──────────────────────────────────────────────┘                      │
│                                                                          │
│   ┌────────────────────────────────────────────────┐                    │
│   │                    common/                      │                    │
│   │  CashFlowId, CashChangeId, CategoryName, Type  │                    │
│   │  Money, UserId, RecurringRuleId                │                    │
│   └────────────────────────────────────────────────┘                    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Struktura pakietu

```
src/main/java/com/multi/vidulum/
├── recurring_rules/
│   ├── domain/
│   │   ├── RecurringRule.java              # Aggregate root
│   │   ├── RecurringRuleId.java            # Value object
│   │   ├── RecurrencePattern.java          # Sealed interface (Monthly, Weekly, etc.)
│   │   ├── RuleName.java
│   │   ├── RuleStatus.java                 # ACTIVE, PAUSED, ENDED
│   │   ├── UpdateMode.java                 # FUTURE_ONLY, ALL_UNMATCHED
│   │   ├── EndReason.java                  # MANUAL, MAX_OCCURRENCES_REACHED, END_DATE_REACHED
│   │   ├── RecurringRuleEvent.java         # Domain events (sealed interface)
│   │   ├── RecurringRuleRepository.java    # Repository interface
│   │   └── exceptions/
│   │       ├── RecurringRuleDoesNotExistException.java
│   │       ├── InvalidRecurrencePatternException.java
│   │       └── RuleAlreadyEndedException.java
│   │
│   ├── app/
│   │   ├── commands/
│   │   │   ├── CreateRecurringRuleCommand.java
│   │   │   ├── CreateRecurringRuleCommandHandler.java
│   │   │   ├── UpdateRecurringRuleCommand.java
│   │   │   ├── UpdateRecurringRuleCommandHandler.java
│   │   │   ├── PauseRecurringRuleCommand.java
│   │   │   ├── PauseRecurringRuleCommandHandler.java
│   │   │   ├── ResumeRecurringRuleCommand.java
│   │   │   ├── ResumeRecurringRuleCommandHandler.java
│   │   │   ├── EndRecurringRuleCommand.java
│   │   │   ├── EndRecurringRuleCommandHandler.java
│   │   │   ├── DeleteRecurringRuleCommand.java
│   │   │   └── DeleteRecurringRuleCommandHandler.java
│   │   ├── queries/
│   │   │   ├── GetRecurringRulesQuery.java
│   │   │   ├── GetRecurringRulesQueryHandler.java
│   │   │   ├── GetRecurringRuleByIdQuery.java
│   │   │   └── GetRecurringRuleByIdQueryHandler.java
│   │   ├── eventhandlers/
│   │   │   ├── RecurringRuleCreatedEventHandler.java
│   │   │   ├── RecurringRuleUpdatedEventHandler.java
│   │   │   ├── RecurringRulePausedEventHandler.java
│   │   │   ├── RecurringRuleResumedEventHandler.java
│   │   │   ├── RecurringRuleEndedEventHandler.java
│   │   │   ├── RecurringRuleDeletedEventHandler.java
│   │   │   └── CashFlowMonthRolledOverEventHandler.java  # Kafka listener
│   │   ├── RecurringRuleRestController.java
│   │   ├── RecurringRuleGenerationService.java  # Calculates occurrences
│   │   └── RecurringRulesAppConfig.java
│   │
│   └── infrastructure/
│       ├── RecurringRuleMongoRepository.java
│       ├── CashFlowHttpClient.java          # HTTP client to CashFlow API
│       └── entity/
│           └── RecurringRuleEntity.java
```

---

## 4. Domain Events

### RecurringRuleEvent - Complete Definition

```java
package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import java.time.*;
import java.util.List;

public sealed interface RecurringRuleEvent extends DomainEvent {

    RecurringRuleId ruleId();
    CashFlowId cashFlowId();
    ZonedDateTime occurredAt();

    // ══════════════════════════════════════════════════════════════
    // LIFECYCLE EVENTS
    // ══════════════════════════════════════════════════════════════

    /**
     * Reguła została utworzona.
     *
     * EFEKT → Wygeneruj ExpectedCashChanges do horyzontu (activePeriod + 11 msc)
     * ACTION: POST /api/v1/cash-flow/{id}/expected-cash-changes/batch
     */
    record RecurringRuleCreatedEvent(
        RecurringRuleId ruleId,
        CashFlowId cashFlowId,
        UserId userId,
        RuleName name,
        Description description,
        Money amount,
        boolean amountIsEstimate,
        Type type,
        CategoryName category,
        RecurrencePattern pattern,
        LocalDate startDate,
        LocalDate endDate,           // nullable
        Integer maxOccurrences,      // nullable
        List<Month> activeMonths,    // empty = all months
        List<LocalDate> excludedDates,
        String counterpartyName,     // nullable, for future matching
        String counterpartyAccount,  // nullable
        ZonedDateTime createdAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return createdAt;
        }
    }

    /**
     * Reguła została zaktualizowana.
     *
     * EFEKT → Zależy od updateMode:
     *   - FUTURE_ONLY: usuń przyszłe ExpectedCC, wygeneruj nowe
     *   - ALL_UNMATCHED: usuń wszystkie EXPECTED (nie CONFIRMED), wygeneruj nowe
     *
     * ACTION:
     *   1. DELETE /api/v1/cash-flow/{id}/expected-cash-changes?sourceRuleId={ruleId}&status=EXPECTED
     *   2. POST /api/v1/cash-flow/{id}/expected-cash-changes/batch
     */
    record RecurringRuleUpdatedEvent(
        RecurringRuleId ruleId,
        CashFlowId cashFlowId,
        UpdateMode updateMode,       // FUTURE_ONLY, ALL_UNMATCHED
        // All potentially changed fields:
        RuleName name,
        Description description,
        Money amount,
        boolean amountIsEstimate,
        CategoryName category,
        RecurrencePattern pattern,
        LocalDate endDate,
        Integer maxOccurrences,
        List<Month> activeMonths,
        List<LocalDate> excludedDates,
        ZonedDateTime updatedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return updatedAt;
        }
    }

    /**
     * Reguła została wstrzymana.
     *
     * EFEKT → Opcjonalnie usuń przyszłe EXPECTED
     * ACTION: DELETE /api/v1/cash-flow/{id}/expected-cash-changes?sourceRuleId={ruleId}&fromDate={today}
     *
     * User decyduje: "Delete generated future transactions?"
     */
    record RecurringRulePausedEvent(
        RecurringRuleId ruleId,
        CashFlowId cashFlowId,
        boolean deleteGeneratedTransactions,  // user choice
        ZonedDateTime pausedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return pausedAt;
        }
    }

    /**
     * Reguła została wznowiona.
     *
     * EFEKT → Wygeneruj ExpectedCashChanges od resumeFrom do horyzontu
     * ACTION: POST /api/v1/cash-flow/{id}/expected-cash-changes/batch
     *
     * User decyduje: "Generate for missed periods since pause?"
     */
    record RecurringRuleResumedEvent(
        RecurringRuleId ruleId,
        CashFlowId cashFlowId,
        boolean generateMissedPeriods,  // user choice
        LocalDate resumeFrom,           // today or pause date
        ZonedDateTime resumedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return resumedAt;
        }
    }

    /**
     * Reguła została zakończona (manual lub auto).
     *
     * EFEKT → Usuń przyszłe EXPECTED (po endDate)
     * ACTION: DELETE /api/v1/cash-flow/{id}/expected-cash-changes?sourceRuleId={ruleId}&fromDate={effectiveEndDate}
     */
    record RecurringRuleEndedEvent(
        RecurringRuleId ruleId,
        CashFlowId cashFlowId,
        EndReason reason,  // MANUAL, MAX_OCCURRENCES_REACHED, END_DATE_REACHED
        LocalDate effectiveEndDate,
        ZonedDateTime endedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return endedAt;
        }
    }

    /**
     * Reguła została usunięta.
     *
     * EFEKT → Usuń WSZYSTKIE ExpectedCashChanges z tej reguły (status=EXPECTED)
     * ACTION: DELETE /api/v1/cash-flow/{id}/expected-cash-changes?sourceRuleId={ruleId}&status=EXPECTED
     *
     * UWAGA: Nie usuwaj CONFIRMED/PAID - te już "się stały"
     */
    record RecurringRuleDeletedEvent(
        RecurringRuleId ruleId,
        CashFlowId cashFlowId,
        boolean deleteConfirmedTransactions,  // user choice, default=false
        ZonedDateTime deletedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return deletedAt;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // GENERATION EVENTS (tracking, emitted after HTTP call success)
    // ══════════════════════════════════════════════════════════════

    /**
     * ExpectedCashChanges zostały wygenerowane i utworzone w CashFlow.
     *
     * EFEKT → Tracking only - CashFlow już ma dane (przez HTTP)
     * Use cases: audit, statistics, debugging
     */
    record ExpectedCashChangesGeneratedEvent(
        RecurringRuleId ruleId,
        CashFlowId cashFlowId,
        int count,
        YearMonth fromPeriod,
        YearMonth toPeriod,
        List<CashChangeId> generatedIds,  // returned by CashFlow API
        ZonedDateTime generatedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return generatedAt;
        }
    }

    /**
     * ExpectedCashChanges zostały usunięte z CashFlow.
     */
    record ExpectedCashChangesRemovedEvent(
        RecurringRuleId ruleId,
        CashFlowId cashFlowId,
        int count,
        ZonedDateTime removedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return removedAt;
        }
    }
}
```

### Supporting Enums

```java
public enum UpdateMode {
    FUTURE_ONLY,      // Only regenerate future (not yet due) transactions
    ALL_UNMATCHED     // Regenerate all EXPECTED status transactions
}

public enum EndReason {
    MANUAL,                    // User clicked "End rule"
    MAX_OCCURRENCES_REACHED,   // e.g., loan paid off (24/24)
    END_DATE_REACHED           // endDate passed
}

public enum RuleStatus {
    ACTIVE,   // Generating transactions
    PAUSED,   // Temporarily stopped
    ENDED     // Permanently ended
}
```

---

## 5. Komunikacja HTTP API

### Zasada: Anti-Corruption Layer

`recurring_rules` nie zna wewnętrznej struktury `CashFlow` aggregate. Komunikuje się tylko przez HTTP API.

```
┌─────────────────────┐         HTTP API         ┌─────────────────────┐
│   recurring_rules   │ ───────────────────────▶ │     cashflow        │
│                     │                          │                     │
│  RecurringRule      │  POST /expected.../batch │  CashFlow           │
│  (aggregate)        │  DELETE /expected...     │  (aggregate)        │
│                     │  GET /categories         │                     │
│                     │  GET /info               │                     │
└─────────────────────┘                          └─────────────────────┘
```

### Korzyści tego podejścia

1. **Łatwa ekstrakcja do mikroserwisu** - wystarczy zmienić URL w HTTP client
2. **Testowanie** - można mockować HTTP client
3. **Niezależność** - CashFlow API może się zmieniać wewnętrznie
4. **Monitoring** - łatwo logować/mierzyć wywołania HTTP

---

## 6. Flow: Event → HTTP → CashFlow → Forecast

### Kompletny przepływ dla "Create Rule"

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            COMPLETE FLOW                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. USER ACTION                                                              │
│     ┌─────────────┐                                                          │
│     │ Create Rule │                                                          │
│     └──────┬──────┘                                                          │
│            │                                                                 │
│            ▼                                                                 │
│  2. RECURRING RULES MODULE                                                   │
│     ┌─────────────────────────────────────────────────────────────┐         │
│     │ RecurringRule.create(...)                                    │         │
│     │   → emit: RecurringRuleCreatedEvent                          │         │
│     │   → save to MongoDB                                          │         │
│     └──────────────────────────┬──────────────────────────────────┘         │
│                                │                                             │
│                                ▼                                             │
│  3. EVENT HANDLER (w recurring_rules, @EventListener)                        │
│     ┌─────────────────────────────────────────────────────────────┐         │
│     │ @EventListener                                               │         │
│     │ onRuleCreated(RecurringRuleCreatedEvent event) {             │         │
│     │                                                              │         │
│     │   // 1. Get CashFlow info (horizon)                          │         │
│     │   CashFlowInfo info = cashFlowClient.getCashFlowInfo(        │         │
│     │       event.cashFlowId()                                     │         │
│     │   );                                                         │         │
│     │                                                              │         │
│     │   // 2. Calculate occurrence dates                           │         │
│     │   List<LocalDate> dates = generationService.calculateDates(  │         │
│     │       event.pattern(),                                       │         │
│     │       event.startDate(),                                     │         │
│     │       info.horizon()  // activePeriod + 11 months            │         │
│     │   );                                                         │         │
│     │                                                              │         │
│     │   // 3. Call CashFlow HTTP API                               │         │
│     │   BatchCreateResponse response = cashFlowClient              │         │
│     │       .createExpectedCashChangesBatch(                       │         │
│     │           event.cashFlowId(),                                │         │
│     │           event.ruleId(),                                    │         │
│     │           dates.stream().map(d -> new ExpectedCashChangeItem(│         │
│     │               event.name(),                                  │         │
│     │               event.description(),                           │         │
│     │               event.amount(),                                │         │
│     │               event.type(),                                  │         │
│     │               event.category(),                              │         │
│     │               d                                              │         │
│     │           )).toList()                                        │         │
│     │       );                                                     │         │
│     │                                                              │         │
│     │   // 4. Update rule with generation info                     │         │
│     │   rule.recordGeneration(response.createdIds(), info.horizon);│         │
│     │   ruleRepository.save(rule);                                 │         │
│     │ }                                                            │         │
│     └──────────────────────────┬──────────────────────────────────┘         │
│                                │                                             │
│                                │ HTTP POST                                   │
│                                ▼                                             │
│  4. CASHFLOW REST API                                                        │
│     ┌─────────────────────────────────────────────────────────────┐         │
│     │ POST /api/v1/cash-flow/{id}/expected-cash-changes/batch      │         │
│     │                                                              │         │
│     │ CashFlowController.createBatch(request) {                    │         │
│     │   List<CashChangeId> ids = commandGateway.send(              │         │
│     │       new AppendExpectedCashChangesBatchCommand(             │         │
│     │           cashFlowId,                                        │         │
│     │           request.sourceRuleId(),                            │         │
│     │           request.items()                                    │         │
│     │       )                                                      │         │
│     │   );                                                         │         │
│     │   return new BatchCreateResponse(ids);                       │         │
│     │ }                                                            │         │
│     └──────────────────────────┬──────────────────────────────────┘         │
│                                │                                             │
│                                ▼                                             │
│  5. CASHFLOW AGGREGATE                                                       │
│     ┌─────────────────────────────────────────────────────────────┐         │
│     │ CashFlow.appendExpectedCashChangesBatch(sourceRuleId, items) │         │
│     │   for (item : items) {                                       │         │
│     │     validate(item);  // category exists, date in range       │         │
│     │     CashChange cc = new CashChange(                          │         │
│     │         generateId(),                                        │         │
│     │         item.name(),                                         │         │
│     │         item.amount(),                                       │         │
│     │         item.category(),                                     │         │
│     │         item.dueDate(),                                      │         │
│     │         CashChangeStatus.EXPECTED,                           │         │
│     │         sourceRuleId  // NEW FIELD: tracking                 │         │
│     │     );                                                       │         │
│     │     cashChanges.add(cc);                                     │         │
│     │   }                                                          │         │
│     │   emit: ExpectedCashChangesBatchAppendedEvent                │         │
│     │ }                                                            │         │
│     └──────────────────────────┬──────────────────────────────────┘         │
│                                │                                             │
│                                │ Kafka                                       │
│                                ▼                                             │
│  6. CASHFLOW FORECAST PROCESSOR                                              │
│     ┌─────────────────────────────────────────────────────────────┐         │
│     │ @KafkaListener(topics = "cash_flow")                         │         │
│     │ onExpectedCashChangesBatchAppended(event) {                  │         │
│     │                                                              │         │
│     │   CashFlowForecastStatement statement = repo.find(...);      │         │
│     │                                                              │         │
│     │   for (cashChange : event.cashChanges()) {                   │         │
│     │     statement.addExpectedTransaction(                        │         │
│     │         cashChange.period(),                                 │         │
│     │         cashChange.category(),                               │         │
│     │         cashChange.amount(),                                 │         │
│     │         cashChange.sourceRuleId()  // tracking               │         │
│     │     );                                                       │         │
│     │   }                                                          │         │
│     │                                                              │         │
│     │   repo.save(statement);                                      │         │
│     │ }                                                            │         │
│     └─────────────────────────────────────────────────────────────┘         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. CashFlow API Endpoints

### Nowe endpointy dla recurring_rules

```java
@RestController
@RequestMapping("/api/v1/cash-flow")
@RequiredArgsConstructor
public class CashFlowController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    // ══════════════════════════════════════════════════════════════
    // BATCH OPERATIONS (dla recurring_rules)
    // ══════════════════════════════════════════════════════════════

    /**
     * Batch create expected cash changes.
     * Used by recurring_rules module when creating/resuming rules.
     *
     * @param cashFlowId the target cash flow
     * @param request batch of expected cash changes with sourceRuleId
     * @return list of created cash change IDs
     */
    @PostMapping("/{cashFlowId}/expected-cash-changes/batch")
    public ResponseEntity<BatchCreateResponse> createExpectedCashChangesBatch(
            @PathVariable String cashFlowId,
            @RequestBody @Valid BatchCreateExpectedCashChangesRequest request
    ) {
        List<CashChangeId> ids = commandGateway.send(
            new AppendExpectedCashChangesBatchCommand(
                CashFlowId.of(cashFlowId),
                request.sourceRuleId() != null
                    ? RecurringRuleId.of(request.sourceRuleId())
                    : null,
                request.items().stream()
                    .map(this::toCommandItem)
                    .toList()
            )
        );

        return ResponseEntity.ok(new BatchCreateResponse(
            ids.stream().map(CashChangeId::id).toList(),
            ids.size()
        ));
    }

    /**
     * Batch delete expected cash changes by source rule.
     * Used when rule is paused/ended/deleted.
     *
     * @param cashFlowId the target cash flow
     * @param sourceRuleId only delete cash changes from this rule
     * @param fromDate optional: only delete from this date onwards
     * @param status optional: only delete with this status (default: EXPECTED)
     * @return count of deleted cash changes
     */
    @DeleteMapping("/{cashFlowId}/expected-cash-changes")
    public ResponseEntity<BatchDeleteResponse> deleteExpectedCashChanges(
            @PathVariable String cashFlowId,
            @RequestParam String sourceRuleId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false, defaultValue = "EXPECTED") CashChangeStatus status
    ) {
        int deleted = commandGateway.send(
            new DeleteExpectedCashChangesByRuleCommand(
                CashFlowId.of(cashFlowId),
                RecurringRuleId.of(sourceRuleId),
                fromDate,
                status
            )
        );

        return ResponseEntity.ok(new BatchDeleteResponse(deleted));
    }

    /**
     * Get CashFlow info for validation and horizon calculation.
     * Used by recurring_rules when creating rules.
     */
    @GetMapping("/{cashFlowId}/info")
    public ResponseEntity<CashFlowInfoResponse> getCashFlowInfo(
            @PathVariable String cashFlowId
    ) {
        CashFlowInfo info = queryGateway.send(
            new GetCashFlowInfoQuery(CashFlowId.of(cashFlowId))
        );

        return ResponseEntity.ok(new CashFlowInfoResponse(
            info.cashFlowId().id(),
            info.name(),
            info.activePeriod(),
            info.horizon(),        // activePeriod + 11 months
            info.status().name()
        ));
    }

    /**
     * Get categories for validation when creating rule.
     * Returns only active (non-archived) categories.
     */
    @GetMapping("/{cashFlowId}/categories")
    public ResponseEntity<List<CategoryInfoResponse>> getCategories(
            @PathVariable String cashFlowId,
            @RequestParam(required = false) Type type  // filter by INFLOW/OUTFLOW
    ) {
        List<CategoryInfo> categories = queryGateway.send(
            new GetCategoriesQuery(CashFlowId.of(cashFlowId), type)
        );

        return ResponseEntity.ok(
            categories.stream()
                .map(c -> new CategoryInfoResponse(c.name(), c.type(), c.isArchived()))
                .toList()
        );
    }
}
```

### Request/Response DTOs

```java
// ══════════════════════════════════════════════════════════════
// REQUEST DTOs
// ══════════════════════════════════════════════════════════════

public record BatchCreateExpectedCashChangesRequest(
    @NotNull String sourceRuleId,
    @NotEmpty List<ExpectedCashChangeItemRequest> items
) {}

public record ExpectedCashChangeItemRequest(
    @NotBlank String name,
    String description,
    @NotNull MoneyJson amount,
    @NotNull Type type,
    @NotBlank String categoryName,
    @NotNull LocalDate dueDate
) {}

public record MoneyJson(
    @NotNull BigDecimal amount,
    @NotBlank String currency
) {}

// ══════════════════════════════════════════════════════════════
// RESPONSE DTOs
// ══════════════════════════════════════════════════════════════

public record BatchCreateResponse(
    List<String> createdIds,
    int count
) {}

public record BatchDeleteResponse(
    int deletedCount
) {}

public record CashFlowInfoResponse(
    String cashFlowId,
    String name,
    YearMonth activePeriod,
    YearMonth horizon,        // do kiedy generować (activePeriod + 11)
    String status             // SETUP, ACTIVE, CLOSED
) {}

public record CategoryInfoResponse(
    String name,
    Type type,
    boolean isArchived
) {}
```

### Nowe Commands w CashFlow

```java
/**
 * Batch append expected cash changes from a recurring rule.
 */
public record AppendExpectedCashChangesBatchCommand(
    CashFlowId cashFlowId,
    RecurringRuleId sourceRuleId,  // tracking
    List<ExpectedCashChangeData> items
) implements Command<List<CashChangeId>> {}

public record ExpectedCashChangeData(
    Name name,
    Description description,
    Money amount,
    Type type,
    CategoryName categoryName,
    LocalDate dueDate
) {}

/**
 * Delete expected cash changes by source rule.
 */
public record DeleteExpectedCashChangesByRuleCommand(
    CashFlowId cashFlowId,
    RecurringRuleId sourceRuleId,
    LocalDate fromDate,           // nullable: delete from this date
    CashChangeStatus status       // usually EXPECTED
) implements Command<Integer> {}
```

### Nowe CashFlow Events

```java
// Dodaj do CashFlowEvent.java

/**
 * Batch of ExpectedCashChanges was appended (from recurring rule).
 */
record ExpectedCashChangesBatchAppendedEvent(
    CashFlowId cashFlowId,
    RecurringRuleId sourceRuleId,
    List<CashChangeData> cashChanges,
    ZonedDateTime appendedAt
) implements CashFlowEvent {

    public record CashChangeData(
        CashChangeId id,
        Name name,
        Money amount,
        Type type,
        CategoryName category,
        YearMonth period,
        LocalDate dueDate
    ) {}

    @Override
    public ZonedDateTime occurredAt() {
        return appendedAt;
    }
}

/**
 * ExpectedCashChanges were deleted (rule paused/ended/deleted).
 */
record ExpectedCashChangesDeletedByRuleEvent(
    CashFlowId cashFlowId,
    RecurringRuleId sourceRuleId,
    List<CashChangeId> deletedIds,
    int count,
    ZonedDateTime deletedAt
) implements CashFlowEvent {
    @Override
    public ZonedDateTime occurredAt() {
        return deletedAt;
    }
}
```

---

## 8. CashFlow HTTP Client

### Interface (w recurring_rules)

```java
package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.common.*;
import java.time.LocalDate;
import java.util.List;

/**
 * Client for CashFlow HTTP API.
 *
 * In monolith: calls localhost endpoints.
 * In microservice: calls external CashFlow service.
 */
public interface CashFlowServiceClient {

    /**
     * Create batch of expected cash changes.
     */
    BatchCreateResponse createExpectedCashChangesBatch(
        CashFlowId cashFlowId,
        RecurringRuleId sourceRuleId,
        List<ExpectedCashChangeItem> items
    );

    /**
     * Delete expected cash changes by rule.
     */
    BatchDeleteResponse deleteExpectedCashChanges(
        CashFlowId cashFlowId,
        RecurringRuleId sourceRuleId,
        LocalDate fromDate  // nullable
    );

    /**
     * Get CashFlow info (for validation, horizon calculation).
     */
    CashFlowInfo getCashFlowInfo(CashFlowId cashFlowId);

    /**
     * Get categories (for validation when creating rule).
     */
    List<CategoryInfo> getCategories(CashFlowId cashFlowId, Type type);

    /**
     * Check if CashFlow exists.
     */
    boolean exists(CashFlowId cashFlowId);
}
```

### Implementation

```java
package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.common.*;
import com.multi.vidulum.recurring_rules.app.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CashFlowHttpClient implements CashFlowServiceClient {

    private final WebClient webClient;

    private static final String BASE_PATH = "/api/v1/cash-flow";

    @Override
    public BatchCreateResponse createExpectedCashChangesBatch(
            CashFlowId cashFlowId,
            RecurringRuleId sourceRuleId,
            List<ExpectedCashChangeItem> items
    ) {
        log.info("Creating {} expected cash changes for cashFlow={}, rule={}",
            items.size(), cashFlowId.id(), sourceRuleId.id());

        BatchCreateExpectedCashChangesRequest request =
            new BatchCreateExpectedCashChangesRequest(sourceRuleId.id(), items);

        return webClient.post()
            .uri(BASE_PATH + "/{id}/expected-cash-changes/batch", cashFlowId.id())
            .bodyValue(request)
            .retrieve()
            .bodyToMono(BatchCreateResponse.class)
            .doOnSuccess(r -> log.info("Created {} cash changes", r.count()))
            .doOnError(e -> log.error("Failed to create cash changes: {}", e.getMessage()))
            .block();
    }

    @Override
    public BatchDeleteResponse deleteExpectedCashChanges(
            CashFlowId cashFlowId,
            RecurringRuleId sourceRuleId,
            LocalDate fromDate
    ) {
        log.info("Deleting expected cash changes for cashFlow={}, rule={}, fromDate={}",
            cashFlowId.id(), sourceRuleId.id(), fromDate);

        return webClient.delete()
            .uri(uriBuilder -> uriBuilder
                .path(BASE_PATH + "/{id}/expected-cash-changes")
                .queryParam("sourceRuleId", sourceRuleId.id())
                .queryParamIfPresent("fromDate", Optional.ofNullable(fromDate))
                .queryParam("status", "EXPECTED")
                .build(cashFlowId.id()))
            .retrieve()
            .bodyToMono(BatchDeleteResponse.class)
            .doOnSuccess(r -> log.info("Deleted {} cash changes", r.deletedCount()))
            .doOnError(e -> log.error("Failed to delete cash changes: {}", e.getMessage()))
            .block();
    }

    @Override
    public CashFlowInfo getCashFlowInfo(CashFlowId cashFlowId) {
        log.debug("Getting CashFlow info for {}", cashFlowId.id());

        return webClient.get()
            .uri(BASE_PATH + "/{id}/info", cashFlowId.id())
            .retrieve()
            .bodyToMono(CashFlowInfo.class)
            .block();
    }

    @Override
    public List<CategoryInfo> getCategories(CashFlowId cashFlowId, Type type) {
        log.debug("Getting categories for cashFlow={}, type={}", cashFlowId.id(), type);

        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path(BASE_PATH + "/{id}/categories")
                .queryParamIfPresent("type", Optional.ofNullable(type))
                .build(cashFlowId.id()))
            .retrieve()
            .bodyToFlux(CategoryInfo.class)
            .collectList()
            .block();
    }

    @Override
    public boolean exists(CashFlowId cashFlowId) {
        try {
            getCashFlowInfo(cashFlowId);
            return true;
        } catch (WebClientResponseException.NotFound e) {
            return false;
        }
    }
}
```

### WebClient Configuration

```java
@Configuration
public class RecurringRulesWebClientConfig {

    @Bean
    public WebClient recurringRulesWebClient(
            @Value("${app.cashflow.base-url:http://localhost:${server.port}}") String baseUrl
    ) {
        return WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .filter(logRequest())
            .filter(logResponse())
            .build();
    }

    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("CashFlow API Request: {} {}", request.method(), request.url());
            return Mono.just(request);
        });
    }

    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            log.debug("CashFlow API Response: {}", response.statusCode());
            return Mono.just(response);
        });
    }
}
```

---

## 9. Month Rollover Integration

### Problem: Jak recurring_rules dowiaduje się o nowym miesiącu?

Gdy user zamyka miesiąc w CashFlow, recurring_rules musi dogenerować transakcje na kolejny miesiąc.

### Rozwiązanie: Kafka Listener

```java
package com.multi.vidulum.recurring_rules.app.eventhandlers;

import com.multi.vidulum.cashflow.domain.CashFlowEvent.MonthRolledOverEvent;
import com.multi.vidulum.recurring_rules.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CashFlowMonthRolledOverEventHandler {

    private final RecurringRuleRepository ruleRepository;
    private final CashFlowServiceClient cashFlowClient;
    private final RecurringRuleGenerationService generationService;

    /**
     * Listen for MonthRolledOverEvent from CashFlow.
     * Generate next month's transactions for all active rules.
     */
    @KafkaListener(
        topics = "cash_flow",
        groupId = "recurring_rules",
        containerFactory = "cashFlowEventKafkaListenerContainerFactory"
    )
    public void onMonthRolledOver(CashFlowEvent event) {
        if (!(event instanceof MonthRolledOverEvent e)) {
            return;  // ignore other events
        }

        log.info("Processing MonthRolledOverEvent: cashFlow={}, newPeriod={}",
            e.cashFlowId(), e.newActivePeriod());

        List<RecurringRule> activeRules = ruleRepository
            .findByCashFlowIdAndStatus(e.cashFlowId(), RuleStatus.ACTIVE);

        log.info("Found {} active rules for cashFlow={}",
            activeRules.size(), e.cashFlowId());

        for (RecurringRule rule : activeRules) {
            try {
                generateForNewPeriod(rule, e.newActivePeriod());
            } catch (Exception ex) {
                log.error("Failed to generate for rule={}: {}",
                    rule.getId(), ex.getMessage(), ex);
                // Continue with other rules - don't fail all because of one
            }
        }
    }

    private void generateForNewPeriod(RecurringRule rule, YearMonth newPeriod) {
        // Calculate horizon (newPeriod + 11 months for 12-month rolling window)
        YearMonth horizon = newPeriod.plusMonths(11);

        // Only generate if we haven't already
        if (rule.getLastGeneratedPeriod() != null
                && !rule.getLastGeneratedPeriod().isBefore(horizon)) {
            log.debug("Rule {} already generated up to {}",
                rule.getId(), rule.getLastGeneratedPeriod());
            return;
        }

        // Calculate dates from last generated + 1 to horizon
        YearMonth fromPeriod = rule.getLastGeneratedPeriod() != null
            ? rule.getLastGeneratedPeriod().plusMonths(1)
            : newPeriod;

        List<ExpectedCashChangeItem> items = generationService.generateItems(
            rule, fromPeriod, horizon
        );

        if (items.isEmpty()) {
            log.debug("No new occurrences for rule={} in period {}-{}",
                rule.getId(), fromPeriod, horizon);
            return;
        }

        // Call CashFlow API
        BatchCreateResponse response = cashFlowClient.createExpectedCashChangesBatch(
            rule.getCashFlowId(),
            rule.getId(),
            items
        );

        // Update rule tracking
        rule.recordGeneration(
            response.createdIds().stream()
                .map(CashChangeId::of)
                .toList(),
            horizon
        );
        ruleRepository.save(rule);

        log.info("Generated {} transactions for rule={}, period={}-{}",
            response.count(), rule.getId(), fromPeriod, horizon);
    }
}
```

### Diagram: Month Rollover Flow

```
   CASHFLOW                          RECURRING_RULES
   ┌───────────────┐                 ┌───────────────────────────┐
   │ User: "Close  │    Kafka       │ KafkaListener             │
   │ month"        │───────────────▶│ onMonthRolledOver() {     │
   │               │                 │   for (rule : activeRules)│
   │ emit: Month   │                 │     generateNextMonth()   │──┐
   │ RolledOver    │                 │ }                         │  │
   │ Event         │                 └───────────────────────────┘  │
   └───────────────┘                                                │
         ▲                                                          │
         │                     HTTP POST /batch                     │
         └──────────────────────────────────────────────────────────┘
```

---

## 10. Shared Kernel - Common Types

### Typy do przeniesienia do `common/`

Te typy są używane przez wiele modułów i powinny być w shared kernel:

```java
// com.multi.vidulum.common

// Already in common:
Money
UserId

// Move from cashflow.domain to common:
CashFlowId      // used by: cashflow, recurring_rules, bank_data_ingestion, forecast
CashChangeId    // used by: cashflow, recurring_rules, forecast
CategoryName    // used by: cashflow, recurring_rules, bank_data_ingestion, forecast
Type            // used by: cashflow, recurring_rules, bank_data_ingestion

// New in common:
RecurringRuleId // used by: recurring_rules, cashflow (sourceRuleId tracking)
```

### Migration Steps

1. Create `RecurringRuleId` in `common/`
2. Move `CashFlowId` from `cashflow.domain` to `common/` (keep deprecated alias)
3. Move `CashChangeId` from `cashflow.domain` to `common/`
4. Move `CategoryName` from `cashflow.domain` to `common/`
5. Move `Type` from `cashflow.domain` to `common/`
6. Update all imports across modules

---

## 11. Database Schema

### MongoDB Collection: recurring_rules

```javascript
// Collection: recurring_rules
{
  "_id": "RR10000001",
  "cashFlowId": "CF10000001",
  "userId": "U10000001",

  // Basic info
  "name": "Czynsz",
  "description": "Miesięczny czynsz za mieszkanie",
  "amount": {
    "amount": 2000.00,
    "currency": "PLN"
  },
  "amountIsEstimate": false,
  "type": "OUTFLOW",
  "categoryName": "Mieszkanie",

  // Recurrence pattern (discriminated union)
  "pattern": {
    "_type": "Monthly",      // Monthly | Weekly | Yearly | Quarterly | EveryNDays | Once
    "dayOfMonth": 10,
    "interval": 1            // every month
  },

  // Validity period
  "startDate": "2026-03-01",
  "endDate": null,           // nullable: never ends
  "maxOccurrences": null,    // nullable: unlimited

  // Seasonal / exclusions
  "activeMonths": [],        // empty = all months; [9,10,11,12,1,2,3,4,5,6] = school year
  "excludedDates": [],       // specific dates to skip

  // Matching hints (for future reconciliation)
  "counterpartyName": "Spółdzielnia Mieszkaniowa",
  "counterpartyAccount": "PL61109010140000071219812874",
  "amountTolerance": null,   // ±50 PLN
  "dateTolerance": 5,        // ±5 days

  // Status & tracking
  "status": "ACTIVE",        // ACTIVE | PAUSED | ENDED
  "endReason": null,         // MANUAL | MAX_OCCURRENCES_REACHED | END_DATE_REACHED
  "generatedCount": 12,
  "lastGeneratedPeriod": "2027-02",

  // Metadata
  "createdAt": "2026-02-24T10:00:00Z",
  "lastModifiedAt": "2026-02-24T10:00:00Z",
  "pausedAt": null,
  "endedAt": null,
  "notes": "Płatność do 10-tego każdego miesiąca"
}
```

### Pattern Examples

```javascript
// Monthly: 10th of every month
"pattern": {
  "_type": "Monthly",
  "dayOfMonth": 10,
  "interval": 1
}

// Monthly: last day of month
"pattern": {
  "_type": "Monthly",
  "dayOfMonth": -1,  // special: last day
  "interval": 1
}

// Weekly: every Friday
"pattern": {
  "_type": "Weekly",
  "dayOfWeek": "FRIDAY",
  "interval": 1
}

// Bi-weekly: every 2 Fridays
"pattern": {
  "_type": "Weekly",
  "dayOfWeek": "FRIDAY",
  "interval": 2
}

// Yearly: January 1st
"pattern": {
  "_type": "Yearly",
  "dayOfMonth": 1,
  "monthOfYear": "JANUARY"
}

// Quarterly: 25th of first month of quarter
"pattern": {
  "_type": "Quarterly",
  "dayOfMonth": 25,
  "quarterMonth": 1  // 1st month of quarter
}

// Every N days: every 14 days (bi-weekly)
"pattern": {
  "_type": "EveryNDays",
  "interval": 14,
  "constrainToDayOfWeek": "FRIDAY"  // optional
}

// Once: single occurrence
"pattern": {
  "_type": "Once",
  "dueDate": "2029-03-05"  // balloon payment
}
```

### CashChange Extension: sourceRuleId

```javascript
// In cashflow collection, CashChange subdocument
{
  "_id": "CC10000123",
  "name": "Czynsz",
  "amount": { "amount": 2000.00, "currency": "PLN" },
  "type": "OUTFLOW",
  "categoryName": "Mieszkanie",
  "status": "EXPECTED",
  "dueDate": "2026-04-10T00:00:00Z",
  "paidDate": null,

  // NEW FIELD: tracking source rule
  "sourceRuleId": "RR10000001",  // null for manual transactions

  "created": "2026-02-24T10:00:00Z",
  "lastModification": "2026-02-24T10:00:00Z"
}
```

---

## 12. Migration Plan

### Phase 1: Prepare Common Types (before recurring_rules implementation)

- [ ] Create `RecurringRuleId` in `common/`
- [ ] Move `CashFlowId` to `common/` (with deprecated alias in `cashflow.domain`)
- [ ] Move `CashChangeId` to `common/`
- [ ] Move `CategoryName` to `common/`
- [ ] Move `Type` to `common/`
- [ ] Update imports in all modules

### Phase 2: Add CashFlow API Endpoints

- [ ] Add `POST /{id}/expected-cash-changes/batch` endpoint
- [ ] Add `DELETE /{id}/expected-cash-changes` endpoint (with sourceRuleId filter)
- [ ] Add `GET /{id}/info` endpoint
- [ ] Add `sourceRuleId` field to `CashChange` entity
- [ ] Add `AppendExpectedCashChangesBatchCommand`
- [ ] Add `DeleteExpectedCashChangesByRuleCommand`
- [ ] Add `ExpectedCashChangesBatchAppendedEvent`
- [ ] Add `ExpectedCashChangesDeletedByRuleEvent`
- [ ] Update `cashflow_forecast_processor` to handle new events

### Phase 3: Implement recurring_rules Module

- [ ] Create package structure (`domain/`, `app/`, `infrastructure/`)
- [ ] Implement `RecurringRule` aggregate
- [ ] Implement `RecurrencePattern` sealed interface
- [ ] Implement `RecurringRuleEvent` sealed interface
- [ ] Implement repository (MongoDB)
- [ ] Implement `CashFlowHttpClient`
- [ ] Implement event handlers (ApplicationEvent listeners)
- [ ] Implement Kafka listener for `MonthRolledOverEvent`
- [ ] Implement REST API (`RecurringRuleRestController`)
- [ ] Implement generation service

### Phase 4: Testing

- [ ] Unit tests for `RecurrencePattern` calculations
- [ ] Unit tests for `RecurringRule` aggregate
- [ ] Integration tests with mock HTTP client
- [ ] End-to-end tests with full flow

### Phase 5: Future Microservice Extraction (optional)

- [ ] Extract `recurring_rules` to separate Maven module
- [ ] Configure separate database
- [ ] Replace `localhost` WebClient with external URL
- [ ] Deploy as separate service
- [ ] Add service discovery / load balancing

---

## 13. Podsumowanie relacji

### Event → Action Matrix

| Event w Recurring Rules | Akcja HTTP do CashFlow | Event w CashFlow | Efekt w Forecast |
|------------------------|------------------------|------------------|------------------|
| `RuleCreatedEvent` | `POST .../batch` | `ExpectedCashChangesBatchAppendedEvent` | Dodaj do expected |
| `RuleUpdatedEvent` | `DELETE` + `POST .../batch` | `DeletedEvent` + `AppendedEvent` | Update expected |
| `RulePausedEvent` | `DELETE ?fromDate=today` (opcja) | `DeletedEvent` | Usuń future expected |
| `RuleResumedEvent` | `POST .../batch` | `AppendedEvent` | Dodaj expected |
| `RuleEndedEvent` | `DELETE ?fromDate=endDate` | `DeletedEvent` | Usuń po endDate |
| `RuleDeletedEvent` | `DELETE ?sourceRuleId=X` | `DeletedEvent` | Usuń wszystkie expected |
| *(Kafka)* `MonthRolledOverEvent` | `POST .../batch` | `AppendedEvent` | Dodaj nowy miesiąc |

### Kluczowe zasady architektury

1. **recurring_rules nigdy nie modyfikuje CashFlow bezpośrednio** - tylko przez HTTP API
2. **CashFlow emituje eventy przez Kafka** - `cashflow_forecast_processor` słucha
3. **recurring_rules słucha `MonthRolledOverEvent`** - żeby dogenerować kolejny miesiąc
4. **sourceRuleId w CashChange** - tracking, który umożliwia batch delete
5. **Shared types w `common/`** - minimalne zależności między modułami

### Diagram: Pełna architektura

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                              │
│   USER                                                                       │
│     │                                                                        │
│     │ 1. Create rule / 2. Update / 3. Pause / 4. Delete                     │
│     ▼                                                                        │
│   ┌─────────────────────────────────────────────────────────────┐           │
│   │                    RECURRING_RULES                           │           │
│   │                                                              │           │
│   │  ┌──────────────┐      ┌────────────────────┐               │           │
│   │  │ REST API     │      │ RecurringRule      │               │           │
│   │  │ /rules/*     │─────▶│ Aggregate          │               │           │
│   │  └──────────────┘      │                    │               │           │
│   │                        │ emit: RuleEvent    │               │           │
│   │                        └─────────┬──────────┘               │           │
│   │                                  │                          │           │
│   │                                  ▼                          │           │
│   │                        ┌────────────────────┐               │           │
│   │                        │ EventHandler       │               │           │
│   │                        │ (ApplicationEvent) │               │           │
│   │                        └─────────┬──────────┘               │           │
│   │                                  │                          │           │
│   └──────────────────────────────────┼──────────────────────────┘           │
│                                      │                                       │
│                                      │ HTTP (sync)                          │
│                                      ▼                                       │
│   ┌─────────────────────────────────────────────────────────────┐           │
│   │                       CASHFLOW                               │           │
│   │                                                              │           │
│   │  ┌──────────────────────────────────────────┐               │           │
│   │  │ REST API                                  │               │           │
│   │  │ POST /cash-flow/{id}/expected.../batch    │               │           │
│   │  │ DELETE /cash-flow/{id}/expected...        │               │           │
│   │  └─────────────────────┬────────────────────┘               │           │
│   │                        │                                     │           │
│   │                        ▼                                     │           │
│   │  ┌──────────────┐      ┌────────────────────┐               │           │
│   │  │ Command      │      │ CashFlow           │               │           │
│   │  │ Gateway      │─────▶│ Aggregate          │               │           │
│   │  └──────────────┘      │                    │               │           │
│   │                        │ emit: CashFlowEvent│               │           │
│   │                        └─────────┬──────────┘               │           │
│   │                                  │                          │           │
│   └──────────────────────────────────┼──────────────────────────┘           │
│                                      │                                       │
│                                      │ Kafka (async)                        │
│                                      ▼                                       │
│   ┌─────────────────────────────────────────────────────────────┐           │
│   │                CASHFLOW_FORECAST_PROCESSOR                   │           │
│   │                                                              │           │
│   │  ┌────────────────────┐      ┌────────────────────┐         │           │
│   │  │ KafkaListener      │      │ CashFlowForecast   │         │           │
│   │  │ (cash_flow topic)  │─────▶│ Statement          │         │           │
│   │  └────────────────────┘      │ (projection)       │         │           │
│   │                              └────────────────────┘         │           │
│   │                                                              │           │
│   └─────────────────────────────────────────────────────────────┘           │
│                                                                              │
│                                                                              │
│   ════════════════════════════════════════════════════════════════          │
│   REVERSE FLOW: MonthRollover triggers rule generation                      │
│   ════════════════════════════════════════════════════════════════          │
│                                                                              │
│   CASHFLOW                          RECURRING_RULES                          │
│   ┌───────────────┐                 ┌───────────────────────────┐           │
│   │ User: "Close  │    Kafka       │ KafkaListener             │           │
│   │ month"        │───────────────▶│ onMonthRolledOver() {     │           │
│   │               │                 │   for (rule : activeRules)│           │
│   │ emit: Month   │                 │     generateNextMonth()   │──┐        │
│   │ RolledOver    │                 │ }                         │  │        │
│   │ Event         │                 └───────────────────────────┘  │        │
│   └───────────────┘                                                │        │
│         ▲                                                          │        │
│         │                     HTTP POST /batch                     │        │
│         └──────────────────────────────────────────────────────────┘        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Appendix: Powiązane dokumenty

- `2026-02-14-recurring-rule-engine-design.md` - Funkcjonalny design (UI, user stories, walidacje)
- `CLAUDE.md` - Guidelines dla Claude Code
- `FEATURES_BACKLOG_DETAILED.md` - Backlog z opisem wszystkich features
