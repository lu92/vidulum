# Features Backlog - Detailed Description

Ten dokument zawiera szczegółowy opis wszystkich niezaimplementowanych funkcji z backlogu.

---

## Spis treści

1. [✅ DONE: Integration Tests with JWT Authentication](#1--done-integration-tests-with-jwt-authentication)
2. [✅ DONE: Month Rollover & Ongoing Sync](#2--done-month-rollover--ongoing-sync)
3. [Kafka Dead Letter Queue (DLQ)](#3-kafka-dead-letter-queue-dlq)
4. [✅ PARTIAL: Recurring Rule Engine](#4--partial-recurring-rule-engine)
5. [AI Categorization](#5-ai-categorization)
6. [Intelligent Reconciliation](#6-intelligent-reconciliation)
7. [Alerts & CashChange Lifecycle](#7-alerts--cashchange-lifecycle)
8. [Maven Multi-Module Migration](#8-maven-multi-module-migration)
9. [Canonical CSV Architecture](#9-canonical-csv-architecture)

---

## 1. ✅ DONE: Integration Tests with JWT Authentication

**Plik:** `docs/features-backlog/TODO-integration-tests-with-jwt-authentication.md`
**Priorytet:** WYSOKI
**Szacowany czas:** 4-6 godzin
**Status:** ✅ **UKOŃCZONE** (2026-02-25)

### Co zostało zrobione

1. **Utworzono `AuthenticatedHttpIntegrationTest`** - nowa klasa bazowa z włączoną security
   - Helper method `registerAndAuthenticate()` do rejestracji i autentykacji
   - Przechowywanie tokenów (`accessToken`, `refreshToken`, `userId`)
   - Metody `authenticatedHeaders()` i `unauthenticatedHeaders()`

2. **Zaktualizowano klasy `*HttpActor`** - dodano `setJwtToken()` method
   - ✅ `CashFlowHttpActor`
   - ✅ `BankDataIngestionHttpActor`

3. **Dodano testy security** - `CashFlowSecurityTest`
   - ✅ Test 403 bez tokena
   - ✅ Test 403 z nieprawidłowym tokenem
   - ✅ Test 403 z malformed tokenem
   - ✅ Test POST bez tokena
   - ✅ Testy publicznych endpointów (register, authenticate)

4. **Migracja istniejących testów** - wszystkie zmigrowne
   - ✅ `CashFlowErrorHandlingTest`
   - ✅ `BankDataIngestionHttpIntegrationTest`
   - ✅ `BankDataIngestionErrorHandlingTest`
   - ✅ `HttpCashFlowServiceClientIntegrationTest`
   - ✅ `AuthenticationControllerTest`

5. **Cleanup** - usunięto stary kod
   - ✅ Usunięto `AbstractHttpIntegrationTest`
   - ✅ Usunięto `TestSecurityConfig` z testów

### Zmiana w kodzie produkcyjnym

Dodano `setJwtToken()` do `HttpCashFlowServiceClient.java` - pozwala na testowanie klienta HTTP bez kontekstu request (używane tylko w testach, w produkcji token jest propagowany przez `RequestContextHolder`).

### Testy

- 385 testów przechodzi
- 0 failures, 0 errors
- 3 skipped (z @Disabled)

### Manualne testy

Wykonano pełny flow manualny na Docker:
- ✅ Rejestracja użytkownika z JWT
- ✅ Tworzenie CashFlow z historią
- ✅ Upload CSV
- ✅ Konfiguracja mapowań kategorii
- ✅ Import transakcji
- ✅ Weryfikacja danych

---

## 2. ✅ DONE: Month Rollover & Ongoing Sync

**Plik:** `docs/features-backlog/2026-02-08-month-rollover-ongoing-sync-design.md`
**Priorytet:** WYSOKI
**Szacowany czas:** 30-40 godzin
**Status:** ✅ **UKOŃCZONE** (2026-02-25)

### Co zostało zrobione

Cała funkcjonalność Month Rollover & Ongoing Sync jest już zaimplementowana w kodzie produkcyjnym i przetestowana.

#### Zaimplementowane komponenty

1. **MonthlyRolloverScheduler** (`src/main/java/com/multi/vidulum/cashflow/app/MonthlyRolloverScheduler.java`)
   - Scheduled job uruchamiany 1. dnia każdego miesiąca o 02:00 UTC
   - Cron: `${vidulum.rollover.cron:0 0 2 1 * *}`
   - Obsługuje catch-up rollover (wielomiesięczny)
   - Rollover wszystkich OPEN CashFlow

2. **RolloverMonthCommand & Handler**
   - `RolloverMonthCommand` - komenda rollover
   - `RolloverMonthCommandHandler` - walidacja OPEN status, emit event

3. **MonthRolledOverEvent**
   - Nowy event dla automatycznego rollover
   - Obsługiwany przez Kafka event handlers

4. **ROLLED_OVER status**
   - Nowy status miesiąca pozwalający na Gap Filling
   - Różnica vs ATTESTED: zezwala na import transakcji

5. **Gap Filling**
   - Import do miesięcy ROLLED_OVER
   - Obsługiwany przez `BankDataIngestionService`

6. **Ongoing Sync**
   - Import w trybie OPEN do miesięcy ACTIVE i ROLLED_OVER

### Testy integracyjne

| Test | Plik | Opis |
|------|------|------|
| `shouldRolloverMonthAndTransitionToRolledOverStatus` | `RolloverMonthIntegrationTest.java` | Podstawowy rollover |
| `shouldFailRolloverForSetupModeCashFlow` | `RolloverMonthIntegrationTest.java` | Walidacja SETUP mode |
| `shouldPerformMultipleRolloversSequentially` | `RolloverMonthIntegrationTest.java` | Sekwencyjne rollovery |
| `shouldPerformBatchRolloverCatchUp` | `RolloverMonthIntegrationTest.java` | Catch-up (wiele miesięcy) |
| `shouldImportTransactionsInOpenModeAfterAttestationViaRestApi` | `BankDataIngestionHttpIntegrationTest.java` | Ongoing Sync przez REST |
| `generateCashflowWithRolloverAndGapFilling` | `DualCashflowStatementGeneratorWithRolledOver.java` | Full E2E: SETUP→OPEN→Rollover→Gap Filling |

### Manualne testy (2026-02-25)

Wykonano pełny flow manualny na Docker:
- ✅ Rejestracja użytkownika z JWT
- ✅ Tworzenie CashFlow z historią (SETUP mode, start: 2025-10)
- ✅ Upload CSV z 8 transakcjami historycznymi
- ✅ Konfiguracja mapowań kategorii
- ✅ Import historycznych transakcji
- ✅ Atestacja (SETUP → OPEN)
- ✅ Rollover miesiąca (2026-02 → 2026-03)
- ✅ Gap Filling - import do ROLLED_OVER miesiąca (2026-02)
- ✅ Ongoing Sync - import do ACTIVE miesiąca (2026-03)
- ✅ Walidacja dat przyszłych (prawidłowe odrzucenie)

### Podsumowanie

Funkcjonalność jest kompletna i produkcyjnie gotowa. Wszystkie komponenty z design document zostały zaimplementowane zgodnie ze specyfikacją.

---

## 3. Kafka Dead Letter Queue (DLQ)

**Plik:** `docs/features-backlog/TODO-kafka-dead-letter-queue.md`
**Priorytet:** ŚREDNI
**Szacowany czas:** 8-12 godzin

### Problem

W `HistoricalCashChangeImportedEventHandler` (oraz innych handlerach Kafka) istnieje problem z "poison messages" - wiadomościami które nie mogą być przetworzone.

**Obecne zachowanie:**
1. Handler otrzymuje event
2. Próbuje znaleźć `CashFlowForecastStatement` w MongoDB
3. Jeśli nie znajdzie - retry z exponential backoff (10 prób, ~13 sekund)
4. Po wyczerpaniu prób - **rzuca wyjątek**
5. Kafka consumer nie commituje offsetu
6. Consumer próbuje przetworzyć tę samą wiadomość ponownie
7. **INFINITE LOOP** - cały consumer jest zablokowany

### Konsekwencje

- Jeden uszkodzony event blokuje przetwarzanie wszystkich kolejnych eventów
- Brak widoczności problemu (poza logami WARN)
- System przestaje reagować na nowe eventy

### Kiedy to może wystąpić

1. **Testy z shared Testcontainers** - Kafka zachowuje wiadomości między uruchomieniami
2. **Produkcja - race condition** - Event wysłany zanim CashFlow został zapisany
3. **Produkcja - usunięcie danych** - CashFlow usunięty, ale eventy pozostały
4. **Produkcja - błąd replikacji** - MongoDB replica lag

### Co trzeba zrobić

1. **Utworzyć DLQ topic** - `cash_flow_dlq`
   ```java
   @Bean
   public NewTopic cashFlowDlqTopic() {
       return TopicBuilder.name("cash_flow_dlq")
               .partitions(1)
               .config(TopicConfig.RETENTION_MS_CONFIG, "604800000") // 7 dni
               .build();
   }
   ```

2. **Zdefiniować format DLQ message**
   ```java
   public record DlqMessage(
       String originalTopic,
       String originalKey,
       String originalPayload,
       String errorMessage,
       String exceptionClass,
       int retryCount,
       ZonedDateTime failedAt,
       Map<String, String> metadata
   ) {}
   ```

3. **Zaimplementować `KafkaDlqErrorHandler`**
   - Wysyła failed messages do DLQ topic
   - Loguje ERROR z detalami
   - Pozwala consumerowi kontynuować

4. **Zmodyfikować wszystkie handlery** - używać DLQ zamiast rzucać wyjątki

5. **Dodać metryki Micrometer**
   - `kafka.dlq.messages.total`
   - `kafka.dlq.messages.by_error_type`

6. **Admin API do DLQ** (opcjonalnie)
   - `GET /admin/dlq/messages` - lista wiadomości
   - `POST /admin/dlq/replay/{id}` - ponowne przetworzenie
   - `DELETE /admin/dlq/{id}` - usunięcie

### Architektura

```
┌──────────┐    ┌─────────────┐    ┌─────────────────┐    ┌──────────────┐
│  Kafka   │───▶│  Consumer   │───▶│    Handler      │───▶│   MongoDB    │
│  Topic   │    │             │    │ (processing)    │    │  (success)   │
└──────────┘    └─────────────┘    └────────┬────────┘    └──────────────┘
                                            │
                                            │ (failure after N retries)
                                            ▼
                                   ┌─────────────────┐    ┌──────────────┐
                                   │  DLQ Producer   │───▶│  DLQ Topic   │
                                   └─────────────────┘    └──────────────┘
```

---

## 4. ✅ PARTIAL: Recurring Rule Engine

**Plik:** `docs/features-backlog/2026-02-14-recurring-rule-engine-design.md`
**Status analizy:** `docs/features-backlog/2026-02-28-recurring-rules-implementation-status.md`
**Priorytet:** WYSOKI
**Status:** ✅ **MVP ~80% UKOŃCZONE** (2026-02-28)

### Podsumowanie stanu implementacji

| Kategoria | Zaimplementowane | Brakuje |
|-----------|------------------|---------|
| **Core CRUD** | 100% | 0% |
| **Basic Patterns** | 100% (4/4) | 3 dodatkowe |
| **Seasonal Rules** | 0% | 100% |
| **Error Handling** | ~60% | ~40% |
| **Event Handling** | ~50% | ~50% |
| **Edge Cases** | ~30% | ~70% |
| **AI Features** | 0% | 100% (out of scope MVP) |

### ✅ Zaimplementowane (MVP Complete)

| Funkcjonalność | Status |
|----------------|--------|
| CRUD operations (Create/Read/Update/Delete) | ✅ |
| Patterns: DAILY, WEEKLY, MONTHLY, YEARLY | ✅ |
| Pause/Resume rules | ✅ |
| Soft delete (status DELETED) | ✅ |
| Auto-generation ExpectedCashChanges | ✅ |
| Regenerate endpoint | ✅ |
| Category validation | ✅ |
| AmountChange support | ✅ |
| Event sourcing (RecurringRuleEvent) | ✅ |
| JWT authentication | ✅ |
| Error handling | ✅ |
| Integration tests | ✅ |
| CashFlow Forecast integration | ✅ |

### ❌ Brakuje (v1.1 - Priorytet WYSOKI)

| Funkcjonalność | Opis |
|----------------|------|
| **activeMonths** | Reguły sezonowe (np. przedszkole IX-VI) |
| **excludedDates** | Lista dat do pominięcia |
| **maxOccurrences** | Limit wystąpień (np. 24 raty kredytu) |
| **amountIsEstimate** | Flaga dla kwot przybliżonych |
| **PauseReason enum** | MANUAL, CATEGORY_ARCHIVED, etc. |
| **GenerationStatus** | Tracking stanu generacji |
| **dayOfMonth = -1** | Ostatni dzień miesiąca |

### ❌ Brakuje (v1.2 - Priorytet ŚREDNI)

| Funkcjonalność | Opis |
|----------------|------|
| QUARTERLY pattern | Co kwartał |
| EveryNDays pattern | Co N dni |
| ONCE pattern | Jednorazowa transakcja |
| counterpartyName/Account hints | Dla future reconciliation |
| Category archived handling | Auto-pause przy archiwizacji |
| CashFlowClosedEvent handling | Auto-pause przy zamknięciu CF |
| Retry strategy | Exponential backoff |
| Failed Generation Recovery Job | Scheduled job do retry |

### REST API (zaimplementowane)

```
POST   /api/v1/recurring-rules                  # Utwórz regułę
GET    /api/v1/recurring-rules/{ruleId}         # Szczegóły reguły
GET    /api/v1/recurring-rules/cash-flow/{id}   # Lista reguł dla CashFlow
GET    /api/v1/recurring-rules/user/{userId}    # Lista reguł użytkownika
GET    /api/v1/recurring-rules/me               # Moje reguły
PUT    /api/v1/recurring-rules/{ruleId}         # Edytuj regułę
DELETE /api/v1/recurring-rules/{ruleId}         # Usuń regułę
POST   /api/v1/recurring-rules/{ruleId}/pause   # Wstrzymaj
POST   /api/v1/recurring-rules/{ruleId}/resume  # Wznów
POST   /api/v1/recurring-rules/{ruleId}/regenerate # Regeneruj
```

### Benchmark konkurencji

| Aplikacja | Scheduled Transactions | Auto-detection | Rule Engine |
|-----------|------------------------|----------------|-------------|
| **YNAB** | ✅ Dobre | ❌ Brak | ❌ Brak |
| **Monarch Money** | ✅ Dobre | ✅ Świetne | ✅ Dobre (IF-THEN) |
| **Copilot** | ⚠️ Ograniczone | ✅ Dobre | ❌ Brak |
| **Agicap** | ✅ Świetne (B2B) | ✅ Dobre | ✅ Zaawansowane |
| **Vidulum (obecny)** | ✅ MVP | ❌ Phase 4 | ✅ MVP (80%) |

---

## 5. AI Categorization

**Plik:** `docs/features-backlog/AI_CATEGORIZATION_PLAN.md`
**Priorytet:** ŚREDNI
**Szacowany czas:** 20-30 godzin

### Cel

Automatyczna kategoryzacja transakcji bankowych przy użyciu AI, gdy:
- Brak kategorii z banku (`bankCategory` jest pusty)
- Kategoria bankowa nie ma skonfigurowanego mapowania
- Użytkownik chce otrzymać sugestię dla nowej transakcji

### Założenia architektoniczne

| Założenie | Opis |
|-----------|------|
| **Abstrakcja LLM** | Implementacja niezależna od dostawcy (Claude, OpenAI, Ollama) |
| **Batch processing** | Grupowanie transakcji dla optymalizacji kosztów API |
| **Learning loop** | Akceptacja sugestii tworzy mapowanie na przyszłość |
| **Graceful degradation** | Brak AI nie blokuje importu (fallback na "Uncategorized") |

### Flow

```
STAGING TRANSACTIONS
         │
         ▼
┌─────────────────────────┐
│ Czy istnieje mapping?   │
└─────────────────────────┘
    │              │
   TAK            NIE
    │              │
    ▼              ▼
┌────────────┐  ┌──────────────────────┐
│ Użyj       │  │ AiCategorizationSvc  │
│ mapowania  │  │ - Batch transakcji   │
└────────────┘  │ - Wyślij do LLM      │
    │          │ - Otrzymaj sugestie   │
    │          └──────────────────────┘
    │              │
    ▼              ▼
┌─────────────────────────────────────┐
│          PREVIEW (UI)               │
│ - Pokaż transakcje z sugestiami AI  │
│ - User akceptuje/odrzuca/edytuje    │
└─────────────────────────────────────┘
         │
    ┌────┴────┐
AKCEPTUJ   ODRZUĆ
    │         │
    ▼         ▼
┌────────────────┐  ┌────────────────────┐
│ Utwórz nowe    │  │ User wybiera       │
│ CategoryMapping│  │ kategorię ręcznie  │
│ (auto-learning)│  │ → nowe mapping     │
└────────────────┘  └────────────────────┘
```

### Struktura kodu

```
com.multi.vidulum.ai_categorization/
├── domain/
│   ├── AiCategorySuggestion.java      # confidence, reasoning
│   ├── CategorizationRequest.java
│   └── SuggestionSource.java          # MAPPING | AI | FALLBACK
│
├── app/
│   └── AiCategorizationService.java
│
├── infrastructure/
│   ├── LlmProvider.java               # Interface
│   ├── ClaudeProvider.java            # Anthropic API
│   ├── OpenAiProvider.java            # OpenAI API
│   └── OllamaProvider.java            # Lokalny LLM
```

### Response format z LLM

```json
{
  "suggestions": [
    {
      "transactionId": "TX123",
      "categoryName": "Groceries",
      "parentCategoryName": "Food",
      "confidence": 92,
      "reasoning": "Transaction at 'Biedronka' is a Polish supermarket chain"
    }
  ]
}
```

### Koszty API (szacunkowe)

| Provider | Model | Koszt per 1000 transakcji |
|----------|-------|---------------------------|
| Claude | claude-3-haiku | ~$0.50 |
| OpenAI | gpt-4o-mini | ~$0.30 |
| Ollama | llama3.2 | $0 (lokalnie) |

---

## 6. Intelligent Reconciliation

**Plik:** `docs/features-backlog/2026-02-07-intelligent-cashflow-reconciliation.md`
**Priorytet:** ŚREDNI
**Szacowany czas:** 50-80 godzin (duża funkcja)

### Cel

Zbudować **inteligentny system Cash Flow Forecasting** który:
1. Automatycznie dopasowuje transakcje bankowe do oczekiwanych
2. Automatycznie kategoryzuje transakcje
3. Minimalizuje zaangażowanie użytkownika
4. Generuje prognozy na podstawie Recurring Rules
5. Obsługuje dane z CSV i API bankowego przez jeden pipeline

### Integracje bankowe

| Provider | Cena | Banki EU | Banki PL |
|----------|------|----------|----------|
| **GoCardless (Nordigen)** | DARMOWE (AIS) | 2,300+ | ~263 |
| **Tink (Visa)** | €0.50/user/msc | 6,000+ | 509+ |

### Reconciliation Engine

```
┌────────────────────────────────┐
│    EXPECTED TRANSACTIONS       │  (z Recurring Rules)
│    - Czynsz 1500 PLN          │
│    - Pensja 8000 PLN          │
└───────────────┬────────────────┘
                │
                ▼
        ┌───────────────┐
        │  RECONCILER   │◀────── Matching algorithm
        └───────┬───────┘        (amount, date, description)
                │
                ▼
┌────────────────────────────────┐
│    BANK TRANSACTIONS           │  (z CSV lub API)
│    - Przelew 1500 PLN         │
│    - Wpłata 8000 PLN          │
└────────────────────────────────┘
                │
                ▼
┌────────────────────────────────┐
│         MATCHED PAIRS          │
│  Expected ←→ Bank Transaction  │
│  + Unmatched Expected          │
│  + Unmatched Bank              │
└────────────────────────────────┘
```

### Soft Close

Automatyczne "miękkie" zamykanie miesięcy:
- Wszystkie Expected są matched
- Saldo się zgadza
- Użytkownik może ręcznie zrobić Hard Close (atestacja)

---

## 7. Alerts & CashChange Lifecycle

**Plik:** `docs/features-backlog/2026-02-14-business-analysis-alerts-cashchange-lifecycle.md`
**Priorytet:** ŚREDNI
**Szacowany czas:** 25-35 godzin

### Cel

System alertów dla cash flow forecasting z rozszerzonym lifecycle CashChange.

### Multi-horizon Forecasting (jak Agicap)

| Horyzont | Opis | Źródła danych |
|----------|------|---------------|
| **Short-term** (4-13 tyg) | Bazuje na aktualnych danych | Actual + AP/AR + Recurring |
| **Medium-term** (6 msc) | Aktuals + budżety | Recurring + Debt schedules |
| **Long-term** (rok) | Planowanie strategiczne | Scenariusze, M&A |

### Typy alertów

| Alert | Trigger | Priorytet |
|-------|---------|-----------|
| **Low Balance** | Saldo < threshold | CRITICAL |
| **Missed Payment** | Expected nie matched po due date | HIGH |
| **Unusual Expense** | Transakcja > 2x średniej kategorii | MEDIUM |
| **Budget Exceeded** | Kategoria > miesięczny budżet | MEDIUM |
| **Upcoming Large Expense** | Expected > threshold w ciągu 7 dni | INFO |

### Rozszerzony lifecycle CashChange

```
                    ┌─────────────┐
                    │   PENDING   │  (użytkownik zaplanował)
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ EXPECTED │ │FORECASTED│ │ SKIPPED  │
        │(user)    │ │(recurring)│ │(anulowana)│
        └────┬─────┘ └────┬─────┘ └──────────┘
             │            │
             └─────┬──────┘
                   ▼
             ┌──────────┐
             │ MATCHED  │  (dopasowana do bank transaction)
             └────┬─────┘
                  │
                  ▼
            ┌──────────┐
            │CONFIRMED │  (zweryfikowana)
            └──────────┘
```

---

## 8. Maven Multi-Module Migration

**Plik:** `docs/features-backlog/VID-103-maven-multi-module-migration.md`
**Priorytet:** NISKI
**Szacowany czas:** 15-20 godzin

### Cel

Przekształcenie monolitycznego projektu w strukturę Maven Multi-Module:
- Jeden codebase
- Wiele niezależnych Docker images
- Współdzielony kod

### Docelowa struktura

```
vidulum/                           # ROOT (parent pom)
├── pom.xml                        # Parent POM
│
├── vidulum-common/                # Shared code
│   └── src/main/java/
│       ├── common/                # Money, Ticker, Currency
│       ├── shared/                # CQRS, DDD base
│       └── events/                # Domain events
│
├── vidulum-api/                   # Main REST API
│   ├── Dockerfile
│   └── src/main/java/
│       ├── VidulumApiApplication.java
│       ├── cashflow/
│       ├── portfolio/
│       ├── trading/
│       └── ...
│
├── vidulum-websocket-gateway/     # WebSocket Gateway (NEW)
│   ├── Dockerfile
│   └── src/main/java/
│       └── WebSocketGatewayApplication.java
│
└── vidulum-forecast-processor/    # Kafka processor (OPTIONAL)
    ├── Dockerfile
    └── src/main/java/
```

### Korzyści

| Korzyść | Opis |
|---------|------|
| **Szybsza kompilacja** | Tylko zmienione moduły |
| **Mniejsze Docker images** | Każdy moduł osobno |
| **Lepsza separacja** | Wymuszona przez Maven |
| **Skalowanie** | Każdy serwis osobno |
| **Testowanie** | Izolowane testy per moduł |

### Plan migracji

1. Utworzyć parent POM
2. Przenieść `common/` i `shared/` do `vidulum-common`
3. Przenieść resztę do `vidulum-api`
4. Utworzyć `vidulum-websocket-gateway` (nowy moduł)
5. Zaktualizować CI/CD

---

## 9. Canonical CSV Architecture

**Plik:** `docs/features-backlog/2026-02-08-canonical-csv-architecture.md`
**Priorytet:** NISKI
**Szacowany czas:** 10-15 godzin

### Cel

Zunifikowany format CSV dla wszystkich banków - jeden wewnętrzny format niezależny od źródła.

### Flow

```
┌─────────────────┐    ┌────────────────┐    ┌─────────────────┐
│   Bank CSV      │───▶│   Parser       │───▶│  Canonical CSV  │
│   (różne        │    │   (per bank)   │    │   (unified)     │
│   formaty)      │    │                │    │                 │
└─────────────────┘    └────────────────┘    └─────────────────┘
                                                      │
                                                      ▼
                                              ┌─────────────────┐
                                              │  Import Engine  │
                                              │  (jeden kod)    │
                                              └─────────────────┘
```

### Canonical format

```csv
transactionId,date,description,amount,currency,type,category,counterparty
TX001,2026-01-15,Grocery shopping,-125.50,PLN,OUTFLOW,Food,Biedronka
TX002,2026-01-31,Salary,8000.00,PLN,INFLOW,Income,Employer ABC
```

### Korzyści

- Jeden kod importu dla wszystkich banków
- Łatwe dodawanie nowych banków (tylko nowy parser)
- Testowanie łatwiejsze (jeden format)
- Możliwość eksportu w unified format

---

## Priorytetyzacja

| Priorytet | Feature | Uzasadnienie | Status |
|-----------|---------|--------------|--------|
| ✅ DONE | JWT Integration Tests | Bezpieczeństwo, już znaleziono bug | **UKOŃCZONE 2026-02-25** |
| ✅ DONE | Month Rollover & Ongoing Sync | Blokuje użytkowników po aktywacji | **UKOŃCZONE 2026-02-25** |
| ✅ PARTIAL | Recurring Rules (MVP) | Core feature dla prognozowania | **~80% UKOŃCZONE 2026-02-28** |
| 🔴 WYSOKI | Recurring Rules v1.1 | Seasonal rules, maxOccurrences, edge cases | TODO |
| 🟡 ŚREDNI | Recurring Rules v1.2 | New patterns, CashFlow event handling | TODO |
| 🟡 ŚREDNI | Kafka DLQ | Stabilność produkcji | TODO |
| 🟡 ŚREDNI | AI Categorization | UX improvement | TODO |
| 🟡 ŚREDNI | Alerts | Proactive notifications | TODO |
| 🟡 ŚREDNI | Reconciliation | Automatyzacja | TODO |
| 🟢 NISKI | Maven Multi-Module | Refactoring | TODO |
| 🟢 NISKI | Canonical CSV | Nice to have | TODO |
