# Recurring Rules - Comprehensive Technical Design

**Data:** 2026-02-25
**Wersja:** 1.0
**Status:** Draft
**Autor:** Claude Code (based on existing design documents)

---

## Spis treści

1. [00-overview.md](./00-overview.md) - Ten dokument - przegląd i kontekst
2. [01-rest-api-design.md](./01-rest-api-design.md) - REST API, JSON schemas, walidacje Bean Validation
3. [02-domain-model.md](./02-domain-model.md) - Model domenowy, agregaty, eventy
4. [03-user-journeys.md](./03-user-journeys.md) - User journeys z wywołaniami API i zmianami stanu
5. [04-mongodb-schema.md](./04-mongodb-schema.md) - Schemat MongoDB z indeksami
6. [05-bounded-context-integration.md](./05-bounded-context-integration.md) - Integracja kontekstów (CashFlow, Forecasting)
7. [06-exceptions-and-errors.md](./06-exceptions-and-errors.md) - Katalog BusinessExceptions i kodów błędów
8. [07-test-design.md](./07-test-design.md) - Design testów (unit, API, integration)
9. [08-inconsistencies-and-questions.md](./08-inconsistencies-and-questions.md) - Znalezione błędy, niespójności i pytania bez odpowiedzi

---

## 1. Cel dokumentu

Ten dokument stanowi **kompletny technical design** dla funkcjonalności **Recurring Rules** w systemie Vidulum. Powstał na podstawie analizy:

- Istniejących dokumentów projektowych w `docs/features-backlog/`
- Kodu źródłowego modułu `cashflow` i `cashflow_forecast_processor`
- Wzorców CQRS i Event Sourcing stosowanych w projekcie
- UI mockups z dokumentacji

---

## 2. Scope

### W zakresie (In Scope)

| Funkcjonalność | Opis |
|----------------|------|
| **CRUD Recurring Rules** | Tworzenie, edycja, usuwanie, listowanie reguł cyklicznych |
| **Wzorce powtarzalności** | DAILY, WEEKLY, MONTHLY, YEARLY z pełną konfiguracją |
| **Generowanie transakcji** | Automatyczne tworzenie CashChange na podstawie reguł |
| **Integracja z CashFlow** | Walidacja kategorii, publikacja eventów |
| **Obsługa zmian kwot** | One-time adjustments i permanent changes |
| **Alerty użytkownika** | Powiadomienia o wpływie edycji/usunięcia na przyszłe transakcje |
| **Obsługa błędów** | Retry, circuit breaker, recovery |
| **Monitoring** | Metryki, health checks |

### Poza zakresem (Out of Scope)

| Funkcjonalność | Powód |
|----------------|-------|
| **AI Suggestions** | Osobna funkcjonalność - przyszły rozwój |
| **Pattern Detection** | Wykrywanie wzorców z historycznych transakcji - przyszły rozwój |
| **Multi-currency rules** | Pierwsza wersja obsługuje jedną walutę per reguła |

---

## 3. Architektura wysokopoziomowa

### 3.1 Bounded Contexts

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           SYSTEM VIDULUM                                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────┐    ┌─────────────────────┐    ┌─────────────┐ │
│  │   RECURRING RULES   │    │      CASH FLOW      │    │  CASH FLOW  │ │
│  │   (NEW CONTEXT)     │───▶│      (EXISTING)     │───▶│  FORECAST   │ │
│  │                     │    │                     │    │  PROCESSOR  │ │
│  │  - RecurringRule    │    │  - CashFlow         │    │             │ │
│  │  - RuleExecution    │    │  - CashChange       │    │  - Forecast │ │
│  │  - AmountChange     │    │  - Category         │    │  - Periods  │ │
│  └─────────────────────┘    └─────────────────────┘    └─────────────┘ │
│           │                          ▲                       ▲         │
│           │                          │                       │         │
│           │    HTTP (sync)           │    Kafka (async)      │         │
│           └──────────────────────────┴───────────────────────┘         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Przepływ danych

```
User Request                    Recurring Rules BC              CashFlow BC
     │                                │                              │
     │  POST /recurring-rules         │                              │
     ├───────────────────────────────▶│                              │
     │                                │                              │
     │                                │  Validate category (HTTP)    │
     │                                ├─────────────────────────────▶│
     │                                │◀─────────────────────────────┤
     │                                │                              │
     │                                │  Save RecurringRule          │
     │                                │  (MongoDB)                   │
     │                                │                              │
     │                                │  Publish RuleCreatedEvent    │
     │                                │  (Kafka via Outbox)          │
     │                                ├─────────────────────────────▶│
     │  201 Created                   │                              │
     │◀───────────────────────────────┤                              │
     │                                │                              │
```

### 3.3 Package Structure (NEW module)

```
com.multi.vidulum/
└── recurring_rules/                      # NEW BOUNDED CONTEXT
    ├── domain/
    │   ├── RecurringRule.java           # Aggregate Root
    │   ├── RecurringRuleId.java         # Value Object
    │   ├── RecurrencePattern.java       # Value Object (sealed interface)
    │   ├── AmountChange.java            # Entity
    │   ├── RuleExecution.java           # Entity
    │   ├── RecurringRuleEvent.java      # Domain Events
    │   └── RecurringRuleSnapshot.java   # Snapshot for persistence
    │
    ├── app/
    │   ├── commands/
    │   │   ├── create/
    │   │   │   ├── CreateRecurringRuleCommand.java
    │   │   │   └── CreateRecurringRuleCommandHandler.java
    │   │   ├── update/
    │   │   │   ├── UpdateRecurringRuleCommand.java
    │   │   │   └── UpdateRecurringRuleCommandHandler.java
    │   │   ├── delete/
    │   │   │   ├── DeleteRecurringRuleCommand.java
    │   │   │   └── DeleteRecurringRuleCommandHandler.java
    │   │   └── amount/
    │   │       ├── AddAmountChangeCommand.java
    │   │       └── AddAmountChangeCommandHandler.java
    │   │
    │   ├── queries/
    │   │   ├── GetRecurringRuleQuery.java
    │   │   ├── GetRecurringRuleQueryHandler.java
    │   │   ├── ListRecurringRulesQuery.java
    │   │   ├── ListRecurringRulesQueryHandler.java
    │   │   ├── PreviewDeleteImpactQuery.java
    │   │   └── PreviewDeleteImpactQueryHandler.java
    │   │
    │   └── RecurringRulesApplicationService.java
    │
    ├── infrastructure/
    │   ├── persistence/
    │   │   ├── RecurringRuleDocument.java
    │   │   ├── RecurringRuleMongoRepository.java
    │   │   └── DomainRecurringRuleRepository.java
    │   │
    │   ├── integration/
    │   │   ├── CashFlowHttpClient.java
    │   │   ├── ResilientCashFlowHttpClient.java
    │   │   └── CategoryValidationService.java
    │   │
    │   ├── kafka/
    │   │   ├── RecurringRuleEventPublisher.java
    │   │   ├── CashFlowEventListener.java
    │   │   └── OutboxProcessor.java
    │   │
    │   └── scheduler/
    │       ├── RuleExecutionScheduler.java
    │       └── FailedGenerationRecoveryService.java
    │
    └── api/
        ├── RecurringRulesController.java
        ├── dto/
        │   ├── CreateRecurringRuleRequest.java
        │   ├── UpdateRecurringRuleRequest.java
        │   ├── RecurringRuleResponse.java
        │   ├── AmountChangeRequest.java
        │   └── DeleteImpactPreviewResponse.java
        └── exception/
            ├── RecurringRuleNotFoundException.java
            ├── CategoryValidationException.java
            └── RuleGenerationException.java
```

---

## 4. Kluczowe decyzje architektoniczne

### 4.1 Osobny Bounded Context

**Decyzja:** Recurring Rules jako NOWY bounded context, nie rozszerzenie CashFlow.

**Uzasadnienie:**
- Osobna odpowiedzialność (planowanie vs realizacja)
- Niezależna ewolucja
- Łatwiejsze testowanie
- Możliwość niezależnego skalowania

### 4.2 Synchroniczna walidacja kategorii

**Decyzja:** Walidacja kategorii przez HTTP call do CashFlow przed zapisem reguły.

**Uzasadnienie:**
- Natychmiastowy feedback dla użytkownika
- Spójność danych
- Circuit breaker dla odporności

### 4.3 Outbox Pattern dla eventów

**Decyzja:** Publikacja eventów przez Outbox Pattern zamiast bezpośredniego Kafka publish.

**Uzasadnienie:**
- Gwarancja at-least-once delivery
- Atomowość zapisu + publikacji
- Możliwość retry przy failurach

### 4.4 Idempotentność generowania

**Decyzja:** Unique constraint na (ruleId, dueDate) + idempotency key.

**Uzasadnienie:**
- Zapobiega duplikatom przy retry
- Bezpieczne przy konkurencyjnych wywołaniach

---

## 5. Wymagania niefunkcjonalne

| Wymaganie | Cel | Metryka |
|-----------|-----|---------|
| **Dostępność** | 99.9% | Uptime per miesiąc |
| **Latencja** | < 200ms | P95 dla operacji CRUD |
| **Throughput** | 100 req/s | Szczytowe obciążenie |
| **Recovery** | < 1h | Czas odzyskania po awarii |
| **Data consistency** | Eventually consistent | Max 5s lag |

---

## 6. Zależności

### 6.1 Wewnętrzne

| Moduł | Typ zależności | Cel |
|-------|----------------|-----|
| `cashflow` | HTTP sync | Walidacja kategorii |
| `cashflow_forecast_processor` | Kafka async | Aktualizacja prognoz |
| `security` | Runtime | Autoryzacja |
| `shared` | Compile | CQRS infrastructure |

### 6.2 Zewnętrzne

| Biblioteka | Wersja | Cel |
|------------|--------|-----|
| Spring Boot | 3.2.4+ | Framework |
| MongoDB | 8.0 | Persistence |
| Kafka | 7.8.1 | Events |
| Resilience4j | 2.x | Circuit breaker |

---

## 7. Ryzyka i mitygacje

| Ryzyko | Prawdopodobieństwo | Wpływ | Mitygacja |
|--------|-------------------|-------|-----------|
| CashFlow niedostępny | Średnie | Wysoki | Circuit breaker, graceful degradation |
| Duplikaty transakcji | Niskie | Wysoki | Idempotency key, unique constraints |
| Niespójna kategoria | Średnie | Średni | Event-driven sync, periodic reconciliation |
| Stale orphan rules | Niskie | Niski | OrphanDetectionService, scheduled cleanup |

---

## Następne dokumenty

Przejdź do [01-rest-api-design.md](./01-rest-api-design.md) aby zobaczyć szczegółowy design REST API.
