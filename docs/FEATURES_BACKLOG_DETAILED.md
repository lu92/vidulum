# Features Backlog - Detailed Description

Ten dokument zawiera szczegółowy opis wszystkich niezaimplementowanych funkcji z backlogu.

---

## Spis treści

1. [TODO: Integration Tests with JWT Authentication](#1-todo-integration-tests-with-jwt-authentication)
2. [Kafka Dead Letter Queue (DLQ)](#2-kafka-dead-letter-queue-dlq)
3. [Recurring Rule Engine](#3-recurring-rule-engine)
4. [AI Categorization](#4-ai-categorization)
5. [Month Rollover & Ongoing Sync](#5-month-rollover--ongoing-sync)
6. [Intelligent Reconciliation](#6-intelligent-reconciliation)
7. [Alerts & CashChange Lifecycle](#7-alerts--cashchange-lifecycle)
8. [Maven Multi-Module Migration](#8-maven-multi-module-migration)
9. [Canonical CSV Architecture](#9-canonical-csv-architecture)

---

## 1. TODO: Integration Tests with JWT Authentication

**Plik:** `docs/features-backlog/TODO-integration-tests-with-jwt-authentication.md`
**Priorytet:** WYSOKI
**Szacowany czas:** 4-6 godzin

### Problem

Obecne testy HTTP **wyłączają security całkowicie** i nie testują autentykacji JWT. Oznacza to że testy nie weryfikują:
- Czy walidacja JWT działa poprawnie
- Czy endpointy odrzucają requesty bez tokena (401 Unauthorized)
- Czy endpointy odrzucają requesty z nieprawidłowym/wygasłym tokenem
- Czy role-based authorization działa (403 Forbidden)
- Czy `JwtAuthenticationFilter` przetwarza requesty poprawnie

### Dowód na problem

Podczas upgrade'u Spring Boot 3.5.2 znaleziono bug w `JwtService.java`:
```java
// BUG - zawsze zwracał true dla poprawnego formatu tokena
return (extractedUsername.equals(extractedUsername)) && !isTokenExpired(token);

// POPRAWNE
return (extractedUsername.equals(username)) && !isTokenExpired(token);
```

Ten bug byłby wykryty gdyby testy używały JWT authentication.

### Co trzeba zrobić

1. **Utworzyć `AuthenticatedHttpIntegrationTest`** - nowa klasa bazowa z włączoną security
   - Helper method do rejestracji i autentykacji
   - Przechowywanie tokenów dla kolejnych requestów
   - Metody `authenticatedHeaders()` i `unauthenticatedHeaders()`

2. **Zaktualizować klasy `*HttpActor`** - dodać `setJwtToken()` method
   - `CashFlowHttpActor`
   - `BankDataIngestionHttpActor`
   - inne aktory HTTP

3. **Dodać testy security** - dedykowane testy dla 401/403
   - Test 401 bez tokena
   - Test 401 z nieprawidłowym tokenem
   - Test 401 z wygasłym tokenem
   - Test 403 bez wymaganej roli

4. **Migracja istniejących testów** - jeden po drugim
   - `CashFlowErrorHandlingTest`
   - `BankDataIngestionHttpIntegrationTest`
   - `HttpCashFlowServiceClientIntegrationTest`

5. **Cleanup** - usunąć stary kod wyłączający security
   - `TestSecurityConfig`
   - `app.security.enabled=false`

### Korzyści

- Testy bliższe produkcji (te same filtry security, ta sama walidacja JWT)
- Wykrywanie bugów security wcześnie
- Testowanie autoryzacji (role-based access control)
- Większa pewność przy deploymentach

### Ryzyka

- Wolniejsze testy (każdy test musi się zalogować)
- Więcej kodu setup
- Token expiration w długich testach

---

## 2. Kafka Dead Letter Queue (DLQ)

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

## 3. Recurring Rule Engine

**Plik:** `docs/features-backlog/2026-02-14-recurring-rule-engine-design.md`
**Priorytet:** WYSOKI
**Szacowany czas:** 40-60 godzin (duża funkcja)

### Cel

Stworzyć **Rule Engine** do automatycznego generowania expected CashChanges na podstawie **recurring rules** (reguł powtarzalnych transakcji).

### Przykłady użycia

| Reguła | Opis |
|--------|------|
| Czynsz | 1500 PLN, co miesiąc, 10. dnia |
| Pensja | 8000 PLN, co miesiąc, ostatni dzień roboczy |
| Netflix | 49 PLN, co miesiąc, 15. dnia |
| Ubezpieczenie samochodu | 1200 PLN, co rok, 1 marca |
| Rata kredytu | 2500 PLN, co miesiąc, 5. dnia, do 2030-12-31 |

### Co dostaje użytkownik (MVP)

| Funkcjonalność | Status |
|----------------|--------|
| Tworzenie reguł przez UI | ✅ MVP |
| Auto-generowanie expected transactions | ✅ MVP |
| Pausowanie/wznawianie reguł | ✅ MVP |
| Edycja przyszłych vs wszystkich | ✅ MVP |
| Wykrywanie duplikatów | ✅ MVP |
| Różne częstotliwości (dzień/tydzień/miesiąc/rok) | ✅ MVP |
| Pattern detection (AI) | ❌ Future |
| Auto-matching z bankiem | ❌ Future |
| Sugestie reguł | ❌ Future |

### Model domenowy

```java
@Aggregate
public class RecurringRule {
    RecurringRuleId id;
    CashFlowId cashFlowId;
    String name;
    String description;
    CategoryId categoryId;
    Money amount;
    CashChangeType type;  // INFLOW / OUTFLOW

    // Scheduling
    RecurrencePattern pattern;  // DAILY, WEEKLY, MONTHLY, YEARLY
    int dayOfMonth;             // 1-31 (lub -1 = ostatni dzień)
    DayOfWeek dayOfWeek;        // dla WEEKLY
    int monthOfYear;            // dla YEARLY

    // Lifecycle
    LocalDate startDate;
    LocalDate endDate;          // nullable = bez końca
    RuleStatus status;          // ACTIVE, PAUSED, COMPLETED

    // Audit
    ZonedDateTime created;
    ZonedDateTime lastModified;
    LocalDate lastGeneratedUntil;  // do kiedy wygenerowano transactions
}
```

### REST API

```
POST   /cash-flow/cf={id}/recurring-rules          # Utwórz regułę
GET    /cash-flow/cf={id}/recurring-rules          # Lista reguł
GET    /cash-flow/cf={id}/recurring-rules/{ruleId} # Szczegóły reguły
PUT    /cash-flow/cf={id}/recurring-rules/{ruleId} # Edytuj regułę
DELETE /cash-flow/cf={id}/recurring-rules/{ruleId} # Usuń regułę
POST   /cash-flow/cf={id}/recurring-rules/{ruleId}/pause   # Wstrzymaj
POST   /cash-flow/cf={id}/recurring-rules/{ruleId}/resume  # Wznów
POST   /cash-flow/cf={id}/recurring-rules/generate         # Wygeneruj transakcje
```

### Scheduled Job

```java
@Scheduled(cron = "0 0 1 * * *")  // Codziennie o 01:00
public void generateRecurringTransactions() {
    // 1. Znajdź wszystkie aktywne reguły
    // 2. Dla każdej reguły sprawdź czy trzeba wygenerować transakcje
    // 3. Generuj ExpectedCashChange dla kolejnych X miesięcy
    // 4. Aktualizuj lastGeneratedUntil
}
```

### Benchmark konkurencji

| Aplikacja | Scheduled Transactions | Auto-detection | Rule Engine |
|-----------|------------------------|----------------|-------------|
| **YNAB** | ✅ Dobre | ❌ Brak | ❌ Brak |
| **Monarch Money** | ✅ Dobre | ✅ Świetne | ✅ Dobre (IF-THEN) |
| **Copilot** | ⚠️ Ograniczone | ✅ Dobre | ❌ Brak |
| **Agicap** | ✅ Świetne (B2B) | ✅ Dobre | ✅ Zaawansowane |
| **Vidulum (cel)** | ✅ MVP | ❌ Phase 4 | ✅ MVP |

---

## 4. AI Categorization

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

## 5. Month Rollover & Ongoing Sync

**Plik:** `docs/features-backlog/2026-02-08-month-rollover-ongoing-sync-design.md`
**Priorytet:** WYSOKI
**Szacowany czas:** 30-40 godzin

### Problem

Obecnie system pozwala tylko na **jednorazowy import** CSV podczas trybu SETUP. Po aktywacji CashFlow (przejście do OPEN) nie ma możliwości wgrywania kolejnych plików CSV.

### Nowe możliwości

| Funkcja | Obecny stan | Nowy stan |
|---------|-------------|-----------|
| Import CSV | Tylko w SETUP mode | SETUP + OPEN mode |
| Przejście miesiąca | Manualna atestacja | Automatyczny rollover (scheduled) |
| Import do przeszłych miesięcy | Niemożliwy | Gap Filling |
| Weryfikacja salda | Przy każdej atestacji | Raz na miesiąc |

### Dwa tryby wgrywania danych

| Tryb | Nazwa | Kiedy |
|------|-------|-------|
| **Historical Backfill** | Import historyczny | SETUP mode, przed aktywacją |
| **Ongoing Sync** | Bieżące uzupełnianie | OPEN mode, po aktywacji |

### Statusy miesięcy

```
┌─────────┐    ┌──────────┐    ┌─────────────┐    ┌──────────┐
│ FUTURE  │───▶│  ACTIVE  │───▶│ ROLLED_OVER │───▶│ ATTESTED │
└─────────┘    └──────────┘    └─────────────┘    └──────────┘
                    │                  │
                    │                  │
                    └───── Gap Filling ┘
```

| Status | Opis | Można importować? |
|--------|------|-------------------|
| `FUTURE` | Miesiąc jeszcze nie nadszedł | Nie |
| `ACTIVE` | Bieżący miesiąc | Tak |
| `ROLLED_OVER` | Automatycznie zamknięty | Tak (Gap Filling) |
| `ATTESTED` | Manualnie zatwierdzony | Nie |

### Month Rollover Scheduler

```java
@Scheduled(cron = "0 0 0 1 * *")  // 1. dnia każdego miesiąca o 00:00
public void rolloverMonth() {
    // 1. Znajdź wszystkie CashFlow w statusie OPEN
    // 2. Dla każdego: zamknij aktywny miesiąc (ACTIVE → ROLLED_OVER)
    // 3. Otwórz nowy miesiąc (nowy ACTIVE)
    // 4. Wyślij event MonthRolledOverEvent
}
```

### Balance Verification

Weryfikacja salda wymagana raz na miesiąc przy pierwszym imporcie:

```
┌─────────────────────────────────────────────────────────┐
│ Upload CSV do miesiąca ACTIVE (np. 2026-02)             │
├─────────────────────────────────────────────────────────┤
│ Czy to pierwszy import w tym miesiącu?                  │
├───────────────┬─────────────────────────────────────────┤
│ TAK           │ NIE                                     │
│ ▼             │ ▼                                       │
│ Wymagana      │ Brak wymagania                          │
│ weryfikacja   │ (already verified)                      │
│ salda         │                                         │
└───────────────┴─────────────────────────────────────────┘
```

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

| Priorytet | Feature | Uzasadnienie |
|-----------|---------|--------------|
| 🔴 WYSOKI | JWT Integration Tests | Bezpieczeństwo, już znaleziono bug |
| 🔴 WYSOKI | Month Rollover | Blokuje użytkowników po aktywacji |
| 🔴 WYSOKI | Recurring Rules | Core feature dla prognozowania |
| 🟡 ŚREDNI | Kafka DLQ | Stabilność produkcji |
| 🟡 ŚREDNI | AI Categorization | UX improvement |
| 🟡 ŚREDNI | Alerts | Proactive notifications |
| 🟡 ŚREDNI | Reconciliation | Automatyzacja |
| 🟢 NISKI | Maven Multi-Module | Refactoring |
| 🟢 NISKI | Canonical CSV | Nice to have |
