# Bank Data Ingestion - Analiza i Rozszerzenie

**Data utworzenia**: 2026-02-08
**Cel**: Dokumentacja obecnego flow ingestion oraz planowane rozszerzenia o reconciliation i AI

---

## Spis treści

1. [Obecny Flow - CSV Ingestion](#1-obecny-flow---csv-ingestion)
2. [Planowane rozszerzenia](#2-planowane-rozszerzenia)
3. [Reconciliation Flow](#3-reconciliation-flow)
4. [AI Categorization](#4-ai-categorization)
5. [Bank API Integration (Kontomatik/Salt Edge)](#5-bank-api-integration)

---

## 1. Obecny Flow - CSV Ingestion

### 1.1 Format CSV (Input)

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
TX001,Wynagrodzenie,Przelew od pracodawcy,Wpływy regularne,5000.00,PLN,INFLOW,2025-01-15,2025-01-15,PL123456789,PL987654321
TX002,Zakupy Biedronka,Płatność kartą,Zakupy kartą,156.78,PLN,OUTFLOW,2025-01-16,2025-01-16,PL987654321,
TX003,Netflix,Subskrypcja miesięczna,Rozrywka,52.99,PLN,OUTFLOW,2025-01-17,2025-01-18,,
```

### 1.2 Kolumny CSV

| Kolumna | Wymagana | Opis | Format |
|---------|----------|------|--------|
| `bankTransactionId` | ❌ | Unikalny ID z banku | String (auto-generowany jeśli brak) |
| `name` | ✅ | Nazwa transakcji | String |
| `description` | ❌ | Opis szczegółowy | String |
| `bankCategory` | ❌ | Kategoria z banku | String (default: "Uncategorized") |
| `amount` | ✅ | Kwota (zawsze dodatnia) | BigDecimal (`,` lub `.`) |
| `currency` | ✅ | Waluta | ISO 4217 (PLN, EUR, USD) |
| `type` | ✅ | Typ transakcji | `INFLOW` lub `OUTFLOW` |
| `operationDate` | ✅ | Data operacji | `YYYY-MM-DD`, `DD.MM.YYYY`, `DD/MM/YYYY` |
| `bookingDate` | ❌ | Data księgowania | jak operationDate |
| `sourceAccountNumber` | ❌ | Numer konta źródłowego | IBAN |
| `targetAccountNumber` | ❌ | Numer konta docelowego | IBAN |

### 1.3 Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PHASE 1: UPLOAD CSV                               │
└─────────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────┐     ┌───────────────────┐     ┌─────────────────────┐
│ POST /upload     │────▶│ CsvParserService  │────▶│ List<BankCsvRow>    │
│ (MultipartFile)  │     │ .parse(file)      │     │ + List<ParseError>  │
└──────────────────┘     └───────────────────┘     └─────────────────────┘
                                                            │
     ┌──────────────────────────────────────────────────────┘
     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PHASE 2: STAGE TRANSACTIONS                         │
└─────────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                       StageTransactionsCommandHandler                        │
├──────────────────────────────────────────────────────────────────────────────┤
│  1. Load CashFlowInfo (categories, existing transactions, mode)              │
│  2. Load CategoryMappings for this CashFlow                                  │
│  3. For each BankCsvRow:                                                     │
│     a. Create OriginalTransactionData                                        │
│     b. Find CategoryMapping for (bankCategory, type)                         │
│     c. If mapping exists → Create MappedTransactionData                      │
│     d. If mapping missing → status = PENDING_MAPPING                         │
│     e. Validate (duplicates, dates, CashFlow mode)                           │
│     f. Create StagedTransaction                                              │
│  4. Save all to MongoDB (staged_transactions collection)                     │
│  5. Return StagingSessionId + preview                                        │
└──────────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────┐
│ MongoDB:         │
│ staged_          │
│ transactions     │
└──────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PHASE 3: CONFIGURE MAPPINGS (if needed)                  │
└─────────────────────────────────────────────────────────────────────────────┘
     │
     │  Jeśli są unmapped categories:
     │  POST /mappings { bankCategory → targetCategory, action }
     │  POST /staging/{id}/revalidate
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          PHASE 4: START IMPORT                              │
└─────────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                       StartImportJobCommandHandler                           │
├──────────────────────────────────────────────────────────────────────────────┤
│  Phase 4a: CREATING_CATEGORIES                                               │
│  ─────────────────────────────                                               │
│  For each CategoryToCreate:                                                  │
│     CashFlowServiceClient.createCategory(name, parent, type)                 │
│     → Emits CashFlowEvent (Kafka) → Creates category in CashFlow             │
│                                                                              │
│  Phase 4b: IMPORTING_TRANSACTIONS                                            │
│  ────────────────────────────────                                            │
│  For each valid StagedTransaction:                                           │
│     CashFlowServiceClient.importHistoricalTransaction(...)                   │
│     → Emits HistoricalCashChangeImportedEvent (Kafka)                        │
│     → Creates CashChange in CashFlow aggregate                               │
│     → CashFlowForecastProcessor updates MonthlyForecast                      │
└──────────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│ MongoDB:         │     │ Kafka:           │     │ MongoDB:         │
│ import_jobs      │     │ cash_flow topic  │     │ cashflow         │
└──────────────────┘     └──────────────────┘     │ + forecasts      │
                                                  └──────────────────┘
```

### 1.4 Przykład krok po kroku

#### Input: CSV File

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
TX001,Wynagrodzenie XYZ,Przelew,Wpływy regularne,5000.00,PLN,INFLOW,2025-01-15,,PL111,PL222
TX002,Biedronka,Zakupy spożywcze,Zakupy kartą,156.78,PLN,OUTFLOW,2025-01-16,,,
```

#### Step 1: CSV Parse → BankCsvRow

```java
BankCsvRow(
    bankTransactionId = "TX001",
    name = "Wynagrodzenie XYZ",
    description = "Przelew",
    bankCategory = "Wpływy regularne",
    amount = 5000.00,
    currency = "PLN",
    type = INFLOW,
    operationDate = 2025-01-15,
    bookingDate = null,
    sourceAccountNumber = "PL111",
    targetAccountNumber = "PL222"
)
```

#### Step 2: Stage → StagedTransaction

**Jeśli mapping istnieje** (`Wpływy regularne` → `Salary`):

```java
StagedTransaction(
    stagedTransactionId = "st-uuid-1",
    cashFlowId = "cf-123",
    stagingSessionId = "ss-456",

    originalData = OriginalTransactionData(
        bankTransactionId = "TX001",
        name = "Wynagrodzenie XYZ",
        description = "Przelew",
        bankCategory = "Wpływy regularne",
        money = Money(5000.00, "PLN"),
        type = INFLOW,
        paidDate = 2025-01-15T00:00:00Z
    ),

    mappedData = MappedTransactionData(
        name = "Wynagrodzenie XYZ",
        description = "Przelew",
        categoryName = CategoryName("Salary"),
        parentCategoryName = null,
        money = Money(5000.00, "PLN"),
        type = INFLOW,
        paidDate = 2025-01-15T00:00:00Z
    ),

    validation = TransactionValidation(
        status = VALID,
        errors = [],
        duplicateOf = null
    ),

    createdAt = 2025-01-20T10:00:00Z,
    expiresAt = 2025-01-21T10:00:00Z  // TTL 24h
)
```

**MongoDB Collection: `staged_transactions`**

#### Step 3: Import → CashChange

Po wywołaniu `StartImportJobCommand`:

```java
// CashFlowServiceClient.importHistoricalTransaction() wywołuje:
ImportHistoricalCashChangeCommand(
    cashFlowId = "cf-123",
    category = "Salary",
    name = "Wynagrodzenie XYZ",
    description = "Przelew",
    amount = 5000.00,
    currency = "PLN",
    type = INFLOW,
    dueDate = 2025-01-15,
    paidDate = 2025-01-15
)
```

**→ Kafka Event: `HistoricalCashChangeImportedEvent`**

```java
HistoricalCashChangeImportedEvent(
    cashFlowId = "cf-123",
    cashChangeId = "cc-789",
    category = "Salary",
    name = "Wynagrodzenie XYZ",
    money = Money(5000.00, "PLN"),
    type = INFLOW,
    paidDate = 2025-01-15
)
```

**→ CashFlow Aggregate Update:**

```java
CashChange(
    cashChangeId = CashChangeId("cc-789"),
    name = "Wynagrodzenie XYZ",
    description = "Przelew",
    money = Money(5000.00, "PLN"),
    type = INFLOW,
    categoryName = CategoryName("Salary"),
    status = CONFIRMED,  // historyczne = od razu CONFIRMED
    created = 2025-01-20T10:00:00Z,
    dueDate = 2025-01-15,
    paidDate = 2025-01-15
)
```

**MongoDB Collection: `cashflow` (embedded in CashFlow document)**

#### Step 4: Forecast Update

**→ CashFlowForecastProcessor (Kafka consumer):**

```java
// Aktualizuje CashFlowMonthlyForecast dla 2025-01
CashFlowMonthlyForecast(
    id = "forecast-2025-01",
    cashFlowId = "cf-123",
    period = 2025-01,
    status = SETUP_PENDING,  // historyczny miesiąc

    categories = [
        CategoryForecast(
            categoryName = "Salary",
            type = INFLOW,
            cashChanges = [
                CashChangeSummary(
                    id = "cc-789",
                    name = "Wynagrodzenie XYZ",
                    money = Money(5000.00, "PLN"),
                    status = CONFIRMED
                )
            ],
            totalConfirmed = Money(5000.00, "PLN"),
            totalPending = Money(0, "PLN")
        )
    ],

    totalInflow = Money(5000.00, "PLN"),
    totalOutflow = Money(0, "PLN"),
    balance = Money(5000.00, "PLN")
)
```

**MongoDB Collection: `cashflow_monthly_forecasts`**

### 1.5 Dane w MongoDB - Podsumowanie

| Collection | Co przechowuje | Kiedy tworzone |
|------------|---------------|----------------|
| `category_mappings` | `bankCategory` → `targetCategory` | POST /mappings |
| `staged_transactions` | Tymczasowe transakcje do review | POST /upload (CSV) |
| `import_jobs` | Historia importów, progress, rollback | POST /import |
| `cashflow` | CashFlow aggregate + CashChanges | Import completed |
| `cashflow_monthly_forecasts` | Miesięczne podsumowania | Kafka event handler |

### 1.6 REST API Endpoints

| Method | Endpoint | Opis |
|--------|----------|------|
| POST | `/api/v1/bank-data-ingestion/{cashFlowId}/upload` | Upload CSV |
| POST | `/api/v1/bank-data-ingestion/{cashFlowId}/mappings` | Configure mappings |
| GET | `/api/v1/bank-data-ingestion/{cashFlowId}/mappings` | List mappings |
| GET | `/api/v1/bank-data-ingestion/{cashFlowId}/staging/{sessionId}` | Preview staged |
| POST | `/api/v1/bank-data-ingestion/{cashFlowId}/staging/{sessionId}/revalidate` | Revalidate after mapping |
| POST | `/api/v1/bank-data-ingestion/{cashFlowId}/import` | Start import |
| GET | `/api/v1/bank-data-ingestion/{cashFlowId}/import/{jobId}` | Check progress |
| POST | `/api/v1/bank-data-ingestion/{cashFlowId}/import/{jobId}/rollback` | Rollback |
| POST | `/api/v1/bank-data-ingestion/{cashFlowId}/import/{jobId}/finalize` | Finalize & cleanup |

### 1.7 ValidationStatus enum

```java
enum ValidationStatus {
    VALID,           // OK do importu
    INVALID,         // Błędy walidacji (daty, tryb CashFlow)
    DUPLICATE,       // Już istnieje w CashFlow
    PENDING_MAPPING  // Brak mappingu dla bankCategory
}
```

### 1.8 Kluczowe klasy

| Klasa | Lokalizacja | Opis |
|-------|-------------|------|
| `CsvParserService` | `bank_data_ingestion/app/` | Parsowanie CSV → BankCsvRow |
| `BankCsvRow` | `bank_data_ingestion/domain/` | Rekord z CSV |
| `UploadCsvCommandHandler` | `bank_data_ingestion/app/commands/upload_csv/` | Orchestracja uploadu |
| `StageTransactionsCommandHandler` | `bank_data_ingestion/app/commands/stage_transactions/` | Staging + walidacja |
| `StartImportJobCommandHandler` | `bank_data_ingestion/app/commands/start_import/` | Import do CashFlow |
| `StagedTransaction` | `bank_data_ingestion/domain/` | Tymczasowa transakcja |
| `OriginalTransactionData` | `bank_data_ingestion/domain/` | Oryginalne dane z banku |
| `MappedTransactionData` | `bank_data_ingestion/domain/` | Dane po mappingu |
| `CategoryMapping` | `bank_data_ingestion/domain/` | Mapping bankCategory → targetCategory |
| `ImportJob` | `bank_data_ingestion/domain/` | Stan i progress importu |

---

## 2. Planowane rozszerzenia

### 2.1 Nowe statusy CashChange

```java
enum CashChangeStatus {
    PENDING,      // Oczekuje na potwierdzenie (istniejący)
    CONFIRMED,    // Potwierdzone (istniejący)
    REJECTED,     // Odrzucone (istniejący)
    ARCHIVED,     // Zarchiwizowane (istniejący)
    FORECASTED,   // Wygenerowane przez RecurringRule (nowy)
    UNMATCHED,    // Oczekiwane ale nie dopasowane (nowy)
    WRITTEN_OFF   // Odpis - nie wpływa na statystyki (nowy)
}
```

### 2.2 Rozszerzone CashChange

```java
record CashChange(
    // Istniejące pola
    CashChangeId cashChangeId,
    String name,
    String description,
    Money money,
    Type type,
    CategoryName categoryName,
    CashChangeStatus status,
    ZonedDateTime created,
    LocalDate dueDate,
    LocalDate endDate,

    // Nowe pola
    String bankTransactionId,          // ID z banku (dla deduplication)
    LocalDate paidDate,                 // Data faktycznej płatności
    String counterpartyAccount,         // Numer konta kontrahenta
    String counterpartyName,            // Nazwa kontrahenta
    String merchantCategoryCode,        // MCC z banku
    CashChangeSource source,            // Skąd pochodzi
    RecurringRuleId recurringRuleId,    // Jeśli wygenerowany przez regułę
    CashChangeId matchedWithId,         // Jeśli dopasowany do expected
    MatchingAudit matchingAudit,        // Historia dopasowań
    String rawBankData                  // Surowe dane JSON z banku
)

enum CashChangeSource {
    MANUAL,           // Ręcznie wprowadzone
    BANK_IMPORT,      // Import CSV
    BANK_API,         // Bank API (Kontomatik/Salt Edge)
    RECURRING_RULE    // Wygenerowane przez RecurringRule
}
```

### 2.3 Nowe agregaty

```java
// RecurringRule - generuje FORECASTED transakcje
record RecurringRule(
    RecurringRuleId ruleId,
    CashFlowId cashFlowId,
    String name,
    Money money,
    Type type,
    CategoryName categoryName,
    RecurrencePattern pattern,      // MONTHLY, WEEKLY, etc.
    int dayOfMonth,                 // Dla MONTHLY
    LocalDate startDate,
    LocalDate endDate,              // null = bez końca
    boolean isActive
)

// MatchingAudit - historia dopasowań
record MatchingAudit(
    MatchingMethod method,          // AUTO, MANUAL, AI_SUGGESTED
    int confidenceScore,            // 0-100
    String matchedBy,               // userId lub "system"
    ZonedDateTime matchedAt,
    String notes,
    List<MatchingAttempt> history   // Historia prób
)
```

---

## 3. Reconciliation Flow

### 3.1 Scenariusze dopasowania

```
EXPECTED (FORECASTED)          ACTUAL (BANK IMPORT)
─────────────────────          ────────────────────
Salary 5000 PLN (01.02)   ←→   Wynagrodzenie XYZ 5000 PLN (01.02)
Netflix 52.99 PLN (15.02) ←→   NETFLIX 52.99 PLN (15.02)
Rent 2000 PLN (05.02)     ←→   ???  (brak dopasowania)
???                       ←→   Biedronka 156 PLN (03.02) (nowa)
```

### 3.2 Scoring Algorithm

| Kryterium | Punkty | Opis |
|-----------|--------|------|
| counterpartyAccount match | 50 | Ten sam numer konta |
| amount ±20% | 25 | Podobna kwota |
| date ±10 days | 15 | Podobna data |
| name pattern match | 10 | Podobna nazwa |
| category match | 10 | Ta sama kategoria (bonus) |

**Thresholds:**
- **65+** = AUTO-MATCH (automatyczne dopasowanie)
- **50-64** = SUGGESTION (sugestia dla usera)
- **<50** = NO MATCH (nowa transakcja lub UNMATCHED)

### 3.3 Rozszerzony Flow z Reconciliation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        PHASE 2.5: RECONCILIATION                            │
└─────────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                       ReconciliationService                                  │
├──────────────────────────────────────────────────────────────────────────────┤
│  1. Load FORECASTED/PENDING CashChanges for affected period                  │
│  2. For each StagedTransaction:                                              │
│     a. Calculate matching score vs each expected                             │
│     b. If score >= 65 → AUTO-MATCH, mark as MATCHED                          │
│     c. If score 50-64 → Add to suggestions list                              │
│     d. If score < 50 → Mark as NEW (no expected match)                       │
│  3. Return ReconciliationResult with:                                        │
│     - autoMatched: List<MatchedPair>                                         │
│     - suggestions: List<MatchSuggestion>                                     │
│     - newTransactions: List<StagedTransaction>                               │
│     - unmatchedExpected: List<CashChange>                                    │
└──────────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                       USER REVIEW (UI)                                       │
├──────────────────────────────────────────────────────────────────────────────┤
│  - Approve auto-matches                                                      │
│  - Accept/reject suggestions                                                 │
│  - Categorize new transactions                                               │
│  - Handle unmatched expected (UNMATCHED or WRITTEN_OFF)                      │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. AI Categorization

### 4.1 Kiedy użyć AI?

| Scenariusz | Akcja |
|------------|-------|
| Nowa transakcja, brak mappingu | AI sugeruje kategorię |
| Nowa transakcja, mapping istnieje | Użyj mappingu |
| User nie zgadza się z mappingiem | AI jako alternatywa |

### 4.2 AI Prompt Template

```
Przeanalizuj transakcję bankową i zaproponuj kategorię.

TRANSAKCJA:
- Nazwa: {{name}}
- Opis: {{description}}
- Kwota: {{amount}} {{currency}}
- Typ: {{type}}
- Kategoria z banku: {{bankCategory}}
- Kontrahent: {{counterpartyName}}

DOSTĘPNE KATEGORIE:
{{#each categories}}
- {{name}} ({{type}})
{{/each}}

HISTORIA PODOBNYCH TRANSAKCJI:
{{#each history}}
- "{{name}}" → {{category}} ({{count}} razy)
{{/each}}

Odpowiedz w formacie JSON:
{
  "category": "nazwa_kategorii",
  "confidence": 0-100,
  "reasoning": "krótkie uzasadnienie"
}
```

### 4.3 Koszty AI (Claude API)

| Model | Input | Output | Koszt/transakcja |
|-------|-------|--------|------------------|
| Claude 3 Haiku | $0.25/1M | $1.25/1M | ~$0.0003 |
| Claude 3 Sonnet | $3/1M | $15/1M | ~$0.003 |

**Optymalizacje:**
- Cache podobnych transakcji (merchant name → category)
- Batch processing (wiele transakcji w jednym request)
- Fallback na Haiku jeśli Sonnet timeout

---

## 5. Bank API Integration

### 5.1 Potencjalni providerzy

| Provider | Polska | Pricing | Self-service |
|----------|--------|---------|--------------|
| Salt Edge | ✅ | Custom | ✅ 90 dni test |
| Kontomatik | ✅ | Custom | ⚠️ Wymaga umowy |
| Tink | ✅ ~11 banków | €0.50/user | ❌ Enterprise |

### 5.2 Kontomatik Integration Flow

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Vidulum Web    │────▶│  Kontomatik      │────▶│  Bank (PKO,     │
│  (Frontend)     │     │  SignIn Widget   │     │  mBank, ING...) │
│                 │◀────│  (JavaScript)    │◀────│                 │
└─────────────────┘     └──────────────────┘     └─────────────────┘
        │                       │
        │ sessionId             │ callback
        ▼                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Vidulum Backend (Java/Spring)               │
│  ┌─────────────────┐    ┌──────────────────┐                    │
│  │ KontomatikClient│───▶│ REST calls to    │                    │
│  │ (custom)        │    │ api.kontomatik   │                    │
│  └─────────────────┘    └──────────────────┘                    │
│           │                                                      │
│           ▼                                                      │
│  ┌─────────────────┐    ┌──────────────────┐                    │
│  │ Parse XML/JSON  │───▶│ → Staging flow   │                    │
│  │ transactions    │    │ (reuse existing) │                    │
│  └─────────────────┘    └──────────────────┘                    │
└─────────────────────────────────────────────────────────────────┘
```

### 5.3 Adapter Pattern

```java
interface BankDataProvider {
    ProviderType getType();
    ConnectionResult connect(UserId userId, BankCredentials credentials);
    List<BankTransaction> fetchTransactions(ConnectionId connectionId, DateRange range);
    void disconnect(ConnectionId connectionId);
}

class KontomatikProvider implements BankDataProvider { ... }
class SaltEdgeProvider implements BankDataProvider { ... }
class CsvProvider implements BankDataProvider { ... }  // Adapter dla CSV
```

---

## Changelog

| Data | Zmiana |
|------|--------|
| 2026-02-08 | Utworzenie dokumentu |
| 2026-02-08 | Dodanie sekcji 1: Obecny Flow - CSV Ingestion |

---

## TODO

- [ ] Sekcja 3: Szczegółowy design Reconciliation
- [ ] Sekcja 4: Szczegółowy design AI Categorization
- [ ] Sekcja 5: Szczegółowy design Bank API Integration
- [ ] Sekcja 6: Soft Close mechanism
- [ ] Sekcja 7: RecurringRule aggregate
- [ ] Sekcja 8: Migration strategy
