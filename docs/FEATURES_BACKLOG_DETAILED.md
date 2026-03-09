# Features Backlog - Detailed Description

Ten dokument zawiera szczegółowy opis wszystkich niezaimplementowanych funkcji z backlogu.

---

## Spis treści

1. [✅ DONE: Integration Tests with JWT Authentication](#1--done-integration-tests-with-jwt-authentication)
2. [✅ DONE: Month Rollover & Ongoing Sync](#2--done-month-rollover--ongoing-sync)
3. [Kafka Dead Letter Queue (DLQ)](#3-kafka-dead-letter-queue-dlq)
4. [✅ DONE: Recurring Rule Engine](#4--done-recurring-rule-engine)
5. [AI Categorization](#5-ai-categorization)
6. [Intelligent Reconciliation](#6-intelligent-reconciliation)
7. [Alerts & CashChange Lifecycle](#7-alerts--cashchange-lifecycle)
8. [Maven Multi-Module Migration](#8-maven-multi-module-migration)
9. [Canonical CSV Architecture](#9-canonical-csv-architecture)
10. [🔴 CRITICAL: Resource Ownership Security (VID-132)](#10--critical-resource-ownership-security-vid-132)
11. [🔴 HIGH: Fix Pause/Resume Duplicates + Auto-Resume (VID-145)](#11--high-fix-pauseresume-duplicates--auto-resume-scheduler-vid-145)
12. [🔴 CRITICAL: Enable Scheduling Infrastructure (VID-146)](#12--critical-enable-scheduling-infrastructure-vid-146)
13. [🟡 MEDIUM: System Auth Token for Schedulers (VID-147)](#13--medium-system-auth-token-for-schedulers-vid-147)
14. [🟡 MEDIUM: Recurring Rules Atomicity & Saga (VID-148)](#14--medium-recurring-rules-atomicity--saga-vid-148)

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

## 4. ✅ DONE: Recurring Rule Engine

**Plik:** `docs/features-backlog/2026-02-14-recurring-rule-engine-design.md`
**Priorytet:** WYSOKI
**Status:** ✅ **UKOŃCZONE ~95%** (2026-03-07)

### Podsumowanie stanu implementacji

| Kategoria | Zaimplementowane | Brakuje |
|-----------|------------------|---------|
| **Core CRUD** | 100% | 0% |
| **All Patterns** | 100% (7/7) | 0% |
| **Seasonal Rules** | 100% | 0% |
| **Advanced Options** | 100% | 0% |
| **Error Handling** | 100% | 0% |
| **Integration Tests** | 100% | 0% |
| **AI Features** | 0% | 100% (out of scope) |

### ✅ Zaimplementowane - PEŁNA LISTA

#### Core CRUD & Lifecycle
| Funkcjonalność | Status |
|----------------|--------|
| Create/Read/Update/Delete | ✅ |
| Pause/Resume rules | ✅ |
| Soft delete (status DELETED) | ✅ |
| Auto-complete (po maxOccurrences lub ONCE) | ✅ |
| Regenerate endpoint | ✅ |
| Category validation | ✅ |
| JWT authentication | ✅ |
| Error handling (404, 400, 409, 503) | ✅ |

#### Pattern Types (7/7)
| Pattern | Status | Opis |
|---------|--------|------|
| DAILY | ✅ | intervalDays (1-365) |
| WEEKLY | ✅ | dayOfWeek + intervalWeeks |
| MONTHLY | ✅ | dayOfMonth (1-31 lub -1) + adjustForMonthEnd |
| YEARLY | ✅ | month + dayOfMonth |
| QUARTERLY | ✅ | monthInQuarter (1-3) + dayOfMonth |
| ONCE | ✅ | targetDate + auto-complete |
| EVERY_N_DAYS | ✅ | intervalDays + preferredDayOfWeek |

#### Advanced Options
| Funkcjonalność | Status | Opis |
|----------------|--------|------|
| **activeMonths** | ✅ | Filtrowanie sezonowe (np. opał XI-III) |
| **excludedDates** | ✅ | Lista dat do pominięcia (święta, urlopy) |
| **maxOccurrences** | ✅ | Limit wystąpień + auto-complete |
| **dayOfMonth = -1** | ✅ | Ostatni dzień miesiąca (28/29/30/31) |
| **adjustForMonthEnd** | ✅ | Dostosowanie dla krótszych miesięcy |
| **preferredDayOfWeek** | ✅ | Preferencja dnia dla EVERY_N_DAYS |
| **AmountChanges** | ✅ | ONE_TIME i PERMANENT zmiany kwot |
| **Rule Execution Tracking** | ✅ | Historia wykonań z statusem |

#### Integration & Tests
| Funkcjonalność | Status |
|----------------|--------|
| Auto-generation ExpectedCashChanges | ✅ |
| CashFlow Forecast integration | ✅ |
| Event sourcing (RecurringRuleEvent) | ✅ |
| 9 scenariuszy testów integracyjnych | ✅ |
| Testy wszystkich advanced options | ✅ |

### ❌ Brakuje (v2.0 - Priorytet NISKI)

| Funkcjonalność | Opis | Priorytet |
|----------------|------|-----------|
| ~~**Amount Changes API**~~ | ✅ DONE - Endpointy zaimplementowane | - |
| ~~**GET /me fix**~~ | ✅ OK - działa prawidłowo | - |
| **GET /{ruleId}/impact-preview** | Podgląd wpływu usunięcia reguły na forecast | 🟡 Średni |
| **deleteGeneratedTransactions** | Opcja usuwania CashChanges przy DELETE rule | 🟡 Średni |
| **amountIsEstimate** | Flaga dla kwot przybliżonych | 🟢 Niski |
| **counterpartyName/Account** | Hints dla future reconciliation | 🟢 Niski |
| **executionHistory** | Historia wykonań w response | 🟢 Niski |
| **statistics** | Statystyki reguły w response | 🟢 Niski |
| **Category archived handling** | Auto-pause przy archiwizacji kategorii | 🟢 Niski |
| **CashFlowClosedEvent handling** | Auto-pause przy zamknięciu CF | 🟢 Niski |
| **Batch operations** | Bulk create/update/delete | 🟢 Niski |
| **Pagination** | Paginacja na listach reguł | 🟢 Niski |
| **X-Idempotency-Key** | Idempotentne requesty | 🟢 Niski |

### REST API (kompletne)

```
POST   /api/v1/recurring-rules                  # Utwórz regułę
GET    /api/v1/recurring-rules/{ruleId}         # Szczegóły reguły
GET    /api/v1/recurring-rules/cash-flow/{id}   # Lista reguł dla CashFlow
GET    /api/v1/recurring-rules/user/{userId}    # Lista reguł użytkownika
GET    /api/v1/recurring-rules/me               # Moje reguły
PUT    /api/v1/recurring-rules/{ruleId}         # Edytuj regułę + regeneracja
DELETE /api/v1/recurring-rules/{ruleId}         # Usuń regułę + cleanup
POST   /api/v1/recurring-rules/{ruleId}/pause   # Wstrzymaj (z resumeDate)
POST   /api/v1/recurring-rules/{ruleId}/resume  # Wznów + regeneracja
POST   /api/v1/recurring-rules/{ruleId}/regenerate # Ręczna regeneracja
```

### Benchmark konkurencji

| Aplikacja | Scheduled Transactions | Auto-detection | Rule Engine |
|-----------|------------------------|----------------|-------------|
| **YNAB** | ✅ Dobre | ❌ Brak | ❌ Brak |
| **Monarch Money** | ✅ Dobre | ✅ Świetne | ✅ Dobre (IF-THEN) |
| **Copilot** | ⚠️ Ograniczone | ✅ Dobre | ❌ Brak |
| **Agicap** | ✅ Świetne (B2B) | ✅ Dobre | ✅ Zaawansowane |
| **Vidulum** | ✅ Zaawansowane | ❌ Phase 4 | ✅ Kompletne (95%) |

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
| ✅ DONE | Recurring Rules | Core feature dla prognozowania | **UKOŃCZONE ~95% 2026-03-07** |
| 🟡 ŚREDNI | Kafka DLQ | Stabilność produkcji | TODO |
| 🟡 ŚREDNI | AI Categorization | UX improvement | TODO |
| 🟡 ŚREDNI | Alerts | Proactive notifications | TODO |
| 🟡 ŚREDNI | Reconciliation | Automatyzacja | TODO |
| 🔴 CRITICAL | Resource Ownership Security (VID-132) | Security vulnerability - IDOR | **TODO - PRZED PRODUKCJĄ** |
| 🟢 NISKI | Recurring Rules v2.0 | Amount Changes API, batch ops | TODO |
| 🟢 NISKI | Maven Multi-Module | Refactoring | TODO |
| 🟢 NISKI | Canonical CSV | Nice to have | TODO |

---

## 10. 🔴 CRITICAL: Resource Ownership Security (VID-132)

**Plik:** `docs/features-backlog/VID-132-resource-ownership-security.md`
**Priorytet:** 🔴 KRYTYCZNY (Security)
**Szacowany czas:** 8-12 godzin
**Status:** TODO - **WYMAGANE PRZED PRODUKCJĄ**

### Problem

Aplikacja nie weryfikuje własności zasobów. Każdy uwierzytelniony użytkownik może:
- Odczytywać/modyfikować/usuwać RecurringRules innych użytkowników
- Uzyskać dostęp do CashFlow innych użytkowników
- Importować dane do CashFlow innych użytkowników

Jest to podatność **IDOR (Insecure Direct Object Reference)** - jedna z OWASP Top 10.

### Podatne endpointy

| API | Endpointy | Podatność |
|-----|-----------|-----------|
| RecurringRules | GET/PUT/DELETE `/{ruleId}`, pause, resume, regenerate, amount-changes | Każdy user może operować na dowolnej regule |
| RecurringRules | GET `/user/{userId}` | Każdy user może zobaczyć reguły innych |
| CashFlow | GET/POST/PUT/DELETE `/cf={cashFlowId}/*` | Każdy user może operować na dowolnym CF |
| Bank Data Ingestion | POST `/cf={cashFlowId}/*` | Każdy user może importować do dowolnego CF |

### Dodatkowe problemy

1. **userId w Request Body** - klient może podać dowolne userId (impersonacja)
2. **CORS localhost** - localhost dozwolony w produkcji

### Plan implementacji (7 faz)

1. **Faza 1**: Infrastruktura security
   - `ResourceOwnershipService` - weryfikacja własności
   - `AccessDeniedException` - wyjątek 403
   - `ErrorCode.ACCESS_DENIED`

2. **Faza 2**: Naprawa RecurringRules API (11 endpointów)

3. **Faza 3**: Naprawa CashFlow API

4. **Faza 4**: Naprawa Bank Data Ingestion API

5. **Faza 5**: Konfiguracja CORS dla produkcji

6. **Faza 6**: Usunięcie userId z DTO (pobieranie z JWT)

7. **Faza 7**: Testy security

### Pliki do utworzenia

| Plik | Opis |
|------|------|
| `ResourceOwnershipService.java` | Serwis weryfikacji własności |
| `AccessDeniedException.java` | Wyjątek 403 |
| `application-prod.yml` | Konfiguracja CORS dla produkcji |

### Pliki do modyfikacji

| Plik | Zmiana |
|------|--------|
| `ErrorCode.java` | Dodanie ACCESS_DENIED |
| `ErrorHttpHandler.java` | Handler dla AccessDeniedException |
| `RecurringRulesController.java` | Weryfikacja własności |
| `CreateRuleRequest.java` | Usunięcie userId |
| `CashFlowRestController.java` | Weryfikacja własności |
| `CashFlowDto.java` | Usunięcie userId z CreateCashFlowJson |
| `BankDataIngestionRestController.java` | Weryfikacja własności |
| `SecurityConfiguration.java` | Konfiguracja CORS |
| Testy (4 pliki) | Nowe testy security |

### Oczekiwane HTTP Status Codes

| Scenariusz | Status |
|------------|--------|
| User uzyskuje dostęp do własnego zasobu | 200 OK |
| User uzyskuje dostęp do zasobu innego użytkownika | **403 FORBIDDEN** |
| Zasób nie istnieje | 404 NOT_FOUND |
| Brak/nieprawidłowy token | 401 UNAUTHORIZED |

### Szczegółowa dokumentacja

Pełny design z przykładami kodu: `docs/features-backlog/VID-132-resource-ownership-security.md`

---

## 11. 🔴 HIGH: Fix Pause/Resume Duplicates + Auto-Resume Scheduler (VID-145)

**Priorytet:** 🔴 WYSOKI (Bug fix + new feature)
**Szacowany czas:** 12-16 godzin
**Status:** TODO
**Zależności:** VID-146 (Enable Scheduling Infrastructure)

### Problem

W systemie recurring rules występuje bug z duplikatami cash changes podczas cyklu pause → resume → pause → resume:
- **Pause** zmienia tylko status na PAUSED, ale **nie usuwa** pending cash changes
- **Resume** generuje **nowe** cash changes bez sprawdzenia czy stare istnieją
- **Auto-resume** - mechanizm `PauseInfo.shouldResumeOn()` istnieje w kodzie ale nie jest nigdzie wykorzystywany (brak schedulera)

### Wymagana filozofia biznesowa (Opcja B)

- Paused rule = brak przyszłych płatności w forecast
- Jeśli użytkownik pauzuje regułę, to znaczy że NIE CHCE tych płatności w najbliższym czasie
- Forecast powinien odzwierciedlać rzeczywistość
- resumeDate to planowana intencja wznowienia, nie gwarancja

### Plan implementacji

#### 1. Fix Pause - usuwanie pending przy pauzie
```java
// RecurringRuleService.handle(PauseRuleCommand)
clearGeneratedCashChanges(rule, authToken);  // NEW: przed zmianą statusu
rule.pause(pauseInfo, clock);
ruleRepository.save(rule);
```

#### 2. Fix Resume - clear + generate od nowa
```java
// RecurringRuleService.handle(ResumeRuleCommand)
rule.resume(clock);
ruleRepository.save(rule);
clearGeneratedCashChanges(rule, authToken);    // NEW: clear pozostałe
generateExpectedCashChanges(rule, authToken);   // generate nowe
```

#### 3. Auto-Resume Scheduler
```java
@Scheduled(cron = "${vidulum.recurring-rules.auto-resume.cron:0 0 3 * * *}")
public void autoResumePausedRules() {
    // 1. Find: status=PAUSED, resumeDate != null, resumeDate <= today
    // 2. For each: resume + generate cash changes from resumeDate
}
```

### Acceptance Criteria

1. ✅ Pause usuwa PENDING cash changes (CONFIRMED pozostają)
2. ✅ Resume nie tworzy duplikatów
3. ✅ Cykl pause→resume→pause→resume działa poprawnie
4. ✅ Auto-resume scheduler działa o zaplanowanej porze
5. ✅ Auto-resume generuje cash changes od resumeDate
6. ✅ Reguły bez resumeDate (indefinite) nie są auto-wznawiane
7. ✅ Testy pokrywają wszystkie scenariusze

### Testy do dodania

1. `shouldClearPendingCashChangesWhenPausingRule`
2. `shouldNotCreateDuplicatesOnPauseResumePauseResumeCycle`
3. `shouldAutoResumeRuleOnScheduledDate`
4. `shouldNotAutoResumeRuleBeforeScheduledDate`
5. `shouldGenerateCashChangesFromResumeDateOnAutoResume`
6. `shouldPreserveConfirmedCashChangesWhenPausing`

---

## 12. 🔴 CRITICAL: Enable Scheduling Infrastructure (VID-146)

**Priorytet:** 🔴 KRYTYCZNY (Infrastructure bug)
**Szacowany czas:** 1-2 godziny
**Status:** TODO
**Blokuje:** VID-145

### Problem

**`@EnableScheduling` brakuje w aplikacji!** Istniejący `MonthlyRolloverScheduler` prawdopodobnie NIE DZIAŁA, bo Spring Boot nie uruchamia `@Scheduled` metod bez `@EnableScheduling`.

### Rozwiązanie

```java
// src/main/java/com/multi/vidulum/config/SchedulingConfig.java
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

### Harmonogram schedulerów (UTC)

| Godzina | Scheduler | Opis | Uzasadnienie |
|---------|-----------|------|--------------|
| **02:00** | MonthlyRolloverScheduler | Rollover CashFlow do nowego miesiąca | Istniejący, uruchamia się 1. dnia miesiąca |
| **03:00** | RecurringRuleAutoResumeScheduler | Auto-resume paused rules | **Musi być PO rollover** - daje godzinę marginesu |

**Dlaczego 03:00 a nie 02:30?**
- Rollover może trwać dłużej dla dużej liczby CashFlows
- Bezpieczniejszy margines
- Prostsza konfiguracja (pełne godziny)
- W razie problemów z rollover, auto-resume nie wygeneruje cash changes do nieistniejącego miesiąca

### Konfiguracja

```yaml
# application.yml
vidulum:
  rollover:
    cron: "0 0 2 1 * *"  # 02:00 UTC, 1. dzień miesiąca
  recurring-rules:
    auto-resume:
      enabled: true
      cron: "0 0 3 * * *"  # 03:00 UTC, codziennie
```

### Testy

1. Zweryfikować że `MonthlyRolloverScheduler` zaczyna działać po dodaniu `@EnableScheduling`
2. Test że scheduler uruchamia się zgodnie z cronem (unit test z mockiem Clock)

---

## 13. 🟡 MEDIUM: System Auth Token for Schedulers (VID-147)

**Priorytet:** 🟡 ŚREDNI
**Szacowany czas:** 4-6 godzin
**Status:** TODO
**Zależności:** VID-145 (wymaga tokenu dla komunikacji z CashFlow service)

### Problem

Schedulery (`MonthlyRolloverScheduler`, `RecurringRuleAutoResumeScheduler`) muszą komunikować się z CashFlow service przez HTTP. Obecnie wszystkie endpointy wymagają JWT tokenu użytkownika, ale schedulery działają bez kontekstu użytkownika.

### Opcje rozwiązania

| Opcja | Opis | Zalety | Wady |
|-------|------|--------|------|
| **A) Service Account** | Stałe konto systemowe z tokenem | Proste, audytowalne | Token może wygasnąć |
| **B) Internal Token** | Specjalny token bez expiry | Bezobsługowe | Security risk jeśli wycieknie |
| **C) Direct Domain Access** | Scheduler używa `DomainCashFlowRepository` bezpośrednio | Brak HTTP overhead | Łamie architekturę (coupling) |
| **D) Machine-to-Machine JWT** | Token generowany przy starcie aplikacji | Standardowe rozwiązanie | Wymaga refresh logic |

### Rekomendacja: Opcja D - Machine-to-Machine JWT

```java
@Component
@RequiredArgsConstructor
public class SystemTokenProvider {

    private final JwtService jwtService;
    private final Clock clock;

    private static final String SYSTEM_SUBJECT = "SYSTEM_SCHEDULER";
    private static final Duration TOKEN_VALIDITY = Duration.ofHours(25); // > 24h cron interval

    private volatile String cachedToken;
    private volatile Instant tokenExpiry;

    public String getSystemToken() {
        if (cachedToken == null || Instant.now(clock).isAfter(tokenExpiry.minus(Duration.ofMinutes(5)))) {
            refreshToken();
        }
        return cachedToken;
    }

    private synchronized void refreshToken() {
        cachedToken = jwtService.generateSystemToken(SYSTEM_SUBJECT, TOKEN_VALIDITY);
        tokenExpiry = Instant.now(clock).plus(TOKEN_VALIDITY);
    }
}
```

### Zmiany w JwtService

```java
public String generateSystemToken(String subject, Duration validity) {
    return Jwts.builder()
            .subject(subject)
            .claim("type", "SYSTEM")  // marker dla system tokens
            .issuedAt(Date.from(Instant.now(clock)))
            .expiration(Date.from(Instant.now(clock).plus(validity)))
            .signWith(getSigningKey())
            .compact();
}
```

### Security considerations

1. **Audit log** - logować wszystkie operacje z system token
2. **Rate limiting** - ograniczyć liczbę operacji per token
3. **Monitoring** - alert przy nietypowej aktywności system tokenu
4. **Rotation** - token ważny max 25h, automatycznie odświeżany

### Pliki do utworzenia/modyfikacji

| Plik | Zmiana |
|------|--------|
| `SystemTokenProvider.java` | **NEW** - provider tokenów systemowych |
| `JwtService.java` | Dodać `generateSystemToken()` |
| `RecurringRuleAutoResumeScheduler.java` | Użyć `SystemTokenProvider` |
| `MonthlyRolloverScheduler.java` | Użyć `SystemTokenProvider` (jeśli potrzebuje) |

---

## 14. 🟡 MEDIUM: Recurring Rules Atomicity & Saga (VID-148)

**Plik:** `docs/features-backlog/VID-148-recurring-rules-atomicity-saga.md`
**Priorytet:** ŚREDNI
**Szacowany czas:** 8-16 godzin
**Zależności:** VID-147 (System Auth Token)

### Problem

Operacje Pause/Resume w Recurring Rules nie są atomowe między RecurringRule (MongoDB) a CashFlow (osobny agregat/HTTP call). Może prowadzić do niespójności danych w scenariuszach awarii.

### Obecny flow (clearGeneratedCashChanges):

```
1. batchDeleteExpectedCashChanges() → HTTP call do CashFlow (usuwa CashChanges)
2. rule.clearGeneratedCashChanges()  → aktualizuje listę IDs w obiekcie rule
3. ruleRepository.save(rule)         → zapisuje rule do MongoDB
```

### Scenariusze awarii

| Scenariusz | Opis | Skutek |
|------------|------|--------|
| **Awaria po HTTP delete** | HTTP się udaje, app pada przed save rule | CashChanges usunięte, ale IDs w rule nadal istnieją |
| **Awaria podczas generate** | 5/10 CashChanges utworzonych, app pada | "Osieroce" CashChanges nie śledzone przez rule |
| **Cykliczne Pause/Resume** | Każdy cykl ma małe ryzyko partial failure | Narastanie osieroconych CashChanges |

### Rozwiązania

#### Opcja A: Saga z kompensacją (złożone)
Dodać rollback logikę - skomplikowane do implementacji

#### Opcja B: Idempotentność + Scheduled Reconciliation (rekomendowane)
```java
@Scheduled(cron = "0 0 4 * * *") // 04:00 UTC
public void reconcileRulesWithCashChanges() {
    // Znajdź osieroce CashChanges i usuń
    // Znajdź phantom IDs w rules i usuń
}
```

#### Opcja C: Intent-First (średnia złożoność)
Najpierw zapisać intencję w rule, potem wykonać HTTP call

### Rekomendacja

**Short-term (VID-148a)**: Opcja B - Reconciliation Scheduler
- Niskie ryzyko, self-healing
- Działa obok istniejącego kodu

**Medium-term (VID-148b)**: Opcja C - Intent-First
- Lepsze gwarancje spójności

### Acceptance Criteria

- [ ] Scheduled job reconciliation (04:00 UTC)
- [ ] Wykrywanie i czyszczenie orphaned CashChanges
- [ ] Wykrywanie i usuwanie phantom IDs z rules
- [ ] Logging i metryki

---

## Aktualizacja priorytetyzacji

| Priorytet | Feature | Uzasadnienie | Status |
|-----------|---------|--------------|--------|
| 🔴 CRITICAL | **VID-146: Enable Scheduling** | Istniejący scheduler nie działa! | ✅ DONE |
| 🔴 CRITICAL | VID-132: Resource Ownership | Security vulnerability - IDOR | TODO |
| 🔴 HIGH | **VID-145: Pause/Resume Fix** | Bug z duplikatami, blokuje UX | ✅ DONE |
| 🟡 MEDIUM | **VID-147: System Auth Token** | Wymagany dla schedulerów | TODO |
| 🟡 MEDIUM | **VID-148: Atomicity/Saga** | Spójność danych przy awariach | TODO |
| 🟡 MEDIUM | Kafka DLQ | Stabilność produkcji | TODO |
| 🟡 MEDIUM | AI Categorization | UX improvement | TODO |
| 🟢 LOW | Recurring Rules v2.0 | Nice to have | TODO |
