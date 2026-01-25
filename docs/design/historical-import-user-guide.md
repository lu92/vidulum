# Historical Import - User Journey & Technical Guide

## Spis treści

1. [Cel dokumentu](#cel-dokumentu)
2. [Przegląd procesu](#przegląd-procesu)
3. [Kluczowe pola i ich znaczenie](#kluczowe-pola-i-ich-znaczenie)
4. [User Journey z przykładowymi requestami](#user-journey-z-przykładowymi-requestami)
5. [Bank Data Ingestion (Import CSV)](#bank-data-ingestion-import-csv)
6. [Stany i przejścia](#stany-i-przejścia)
7. [Obsługa błędów](#obsługa-błędów)
8. [Decyzje projektowe](#decyzje-projektowe)
9. [Archiwizacja kategorii](#archiwizacja-kategorii)
10. [Wersjonowanie kategorii (Category Versioning)](#wersjonowanie-kategorii-category-versioning)
11. [Archiwizacja z subkategoriami (forceArchiveChildren)](#archiwizacja-z-subkategoriami-forcearchivechildren)

---

## Cel dokumentu

Ten dokument opisuje kompletny proces importu historycznych transakcji do CashFlow.
Zawiera:
- Przykładowe requesty API
- Wizualizację zmian stanu agregatów
- Opis możliwych błędów i ich obsługi
- Uzasadnienie podjętych decyzji projektowych

---

## Przegląd procesu

### Diagram wysokopoziomowy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    HISTORICAL IMPORT FLOW                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. CREATE           2. IMPORT              3. ATTEST           4. USE     │
│  ───────────────     ──────────────────     ────────────────    ────────── │
│                                                                             │
│  createCashFlow      importHistorical       attestHistorical    appendCash │
│  WithHistory         CashChange (N razy)    Import              Change     │
│                                                                             │
│  Status: SETUP       Status: SETUP          Status: OPEN        Status:    │
│  Months:             Months:                Months:             OPEN       │
│  IMPORT_PENDING      IMPORT_PENDING         IMPORTED                       │
│  ACTIVE              (z transakcjami)       ACTIVE                         │
│  FORECASTED          ACTIVE                 FORECASTED                     │
│                      FORECASTED                                            │
│                                                                             │
│                      [opcjonalnie]                                         │
│                      rollbackImport                                        │
│                      (czyszczenie)                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Kluczowe pola i ich znaczenie

### CashFlow Aggregate

| Pole | Typ | Opis |
|------|-----|------|
| `status` | `CashFlowStatus` | SETUP (import w toku), OPEN (aktywny), CLOSED (zamknięty) |
| `startPeriod` | `YearMonth` | Pierwszy miesiąc historyczny (np. 2021-10) |
| `activePeriod` | `YearMonth` | Bieżący miesiąc ("teraz") |
| `initialBalance` | `Money` | Saldo otwarcia na początku startPeriod |
| `importCutoffDateTime` | `ZonedDateTime` | Znacznik czasowy attestacji - granica między danymi historycznymi a nowymi |

### CashFlowForecastStatement (Read Model)

| Pole | Typ | Opis |
|------|-----|------|
| `forecasts` | `Map<YearMonth, CashFlowMonthlyForecast>` | Mapa miesięcy z prognozami |
| `categoryStructure` | `CurrentCategoryStructure` | Aktualna struktura kategorii |

### CashFlowMonthlyForecast.Status

| Status | Opis | Dozwolone operacje |
|--------|------|-------------------|
| `IMPORT_PENDING` | Miesiąc historyczny oczekujący na import | importHistoricalCashChange |
| `IMPORTED` | Miesiąc historyczny po attestacji | Tylko odczyt |
| `ACTIVE` | Bieżący miesiąc | appendCashChange, confirmCashChange, editCashChange |
| `FORECASTED` | Przyszły miesiąc | appendExpectedCashChange |
| `ATTESTED` | Zamknięty miesiąc (miesięczne rozliczenie) | Tylko odczyt |

---

## User Journey z przykładowymi requestami

### Scenariusz: Import 3 miesięcy historii (Oct-Dec 2021), bieżący miesiąc: Jan 2022

#### Krok 1: Utworzenie CashFlow z historią

**Request:**
```http
POST /api/v1/cash-flow/with-history
Content-Type: application/json

{
  "userId": "user-123",
  "name": "Konto ING",
  "description": "Główne konto osobiste",
  "bankAccount": {
    "bankName": { "name": "ING" },
    "bankAccountNumber": {
      "number": "PL12345678901234567890123456",
      "denomination": { "id": "PLN" }
    },
    "balance": { "amount": 5000, "currency": "PLN" }
  },
  "startPeriod": "2021-10",
  "initialBalance": { "amount": 1000, "currency": "PLN" }
}
```

**Response:**
```json
"cf-abc-123"
```

**Stan po operacji:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlow Aggregate                                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ cashFlowId: cf-abc-123                                                      │
│ status: SETUP                                                               │
│ startPeriod: 2021-10                                                        │
│ activePeriod: 2022-01                                                       │
│ initialBalance: 1000 PLN                                                    │
│ importCutoffDateTime: null  ← jeszcze nie ustawione                         │
│ cashChanges: {}  ← puste                                                    │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastStatement                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ forecasts:                                                                  │
│   2021-10: { status: IMPORT_PENDING, inflows: [], outflows: [] }           │
│   2021-11: { status: IMPORT_PENDING, inflows: [], outflows: [] }           │
│   2021-12: { status: IMPORT_PENDING, inflows: [], outflows: [] }           │
│   2022-01: { status: ACTIVE, inflows: [], outflows: [] }                   │
│   2022-02: { status: FORECASTED, inflows: [], outflows: [] }               │
│   ... (kolejne 10 miesięcy FORECASTED)                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### Krok 2: Import transakcji historycznych

**Request 1 - Wypłata (October):**
```http
POST /api/v1/cash-flow/cf-abc-123/import-historical
Content-Type: application/json

{
  "category": "Uncategorized",
  "name": "Wypłata",
  "description": "Pensja październik",
  "money": { "amount": 5000, "currency": "PLN" },
  "type": "INFLOW",
  "dueDate": "2021-10-10T12:00:00Z",
  "paidDate": "2021-10-10T12:00:00Z"
}
```

**Response:**
```json
"cc-salary-oct"
```

**Request 2 - Czynsz (November):**
```http
POST /api/v1/cash-flow/cf-abc-123/import-historical
Content-Type: application/json

{
  "category": "Uncategorized",
  "name": "Czynsz",
  "description": "Czynsz za mieszkanie",
  "money": { "amount": 2000, "currency": "PLN" },
  "type": "OUTFLOW",
  "dueDate": "2021-11-05T12:00:00Z",
  "paidDate": "2021-11-05T12:00:00Z"
}
```

**Stan po imporcie 2 transakcji:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlow Aggregate                                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ status: SETUP                                                               │
│ cashChanges: {                                                              │
│   cc-salary-oct: { name: "Wypłata", money: 5000 PLN, type: INFLOW }        │
│   cc-rent-nov: { name: "Czynsz", money: 2000 PLN, type: OUTFLOW }          │
│ }                                                                           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastStatement                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ forecasts:                                                                  │
│   2021-10: {                                                                │
│     status: IMPORT_PENDING,                                                 │
│     inflows: [{ name: "Wypłata", 5000 PLN, PAID }],                        │
│     outflows: []                                                            │
│   }                                                                         │
│   2021-11: {                                                                │
│     status: IMPORT_PENDING,                                                 │
│     inflows: [],                                                            │
│     outflows: [{ name: "Czynsz", 2000 PLN, PAID }]                         │
│   }                                                                         │
│   2021-12: { status: IMPORT_PENDING, ... }                                 │
│   2022-01: { status: ACTIVE, ... }                                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Kalkulacja salda:**
```
initialBalance:     1,000 PLN
+ Wypłata Oct:    + 5,000 PLN
- Czynsz Nov:     - 2,000 PLN
────────────────────────────
calculatedBalance:  4,000 PLN
```

---

#### Krok 3: Attestacja importu

##### Scenariusz A: Saldo się zgadza

**Request:**
```http
POST /api/v1/cash-flow/cf-abc-123/attest-historical-import
Content-Type: application/json

{
  "confirmedBalance": { "amount": 4000, "currency": "PLN" },
  "forceAttestation": false,
  "createAdjustment": false
}
```

**Response:**
```json
{
  "cashFlowId": "cf-abc-123",
  "confirmedBalance": { "amount": 4000, "currency": "PLN" },
  "calculatedBalance": { "amount": 4000, "currency": "PLN" },
  "difference": { "amount": 0, "currency": "PLN" },
  "forced": false,
  "adjustmentCreated": false,
  "adjustmentCashChangeId": null,
  "status": "OPEN"
}
```

##### Scenariusz B: Saldo się nie zgadza - użyj createAdjustment

Załóżmy, że użytkownik potwierdza saldo 4500 PLN (różnica +500 PLN).

**Request:**
```http
POST /api/v1/cash-flow/cf-abc-123/attest-historical-import
Content-Type: application/json

{
  "confirmedBalance": { "amount": 4500, "currency": "PLN" },
  "forceAttestation": false,
  "createAdjustment": true
}
```

**Response:**
```json
{
  "cashFlowId": "cf-abc-123",
  "confirmedBalance": { "amount": 4500, "currency": "PLN" },
  "calculatedBalance": { "amount": 4000, "currency": "PLN" },
  "difference": { "amount": 500, "currency": "PLN" },
  "forced": false,
  "adjustmentCreated": true,
  "adjustmentCashChangeId": "cc-adjustment-xyz",
  "status": "OPEN"
}
```

**Stan po attestacji z adjustment:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlow Aggregate                                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ status: OPEN  ← zmiana z SETUP                                              │
│ importCutoffDateTime: 2022-01-15T10:30:00Z  ← ustawione!                   │
│ bankAccount.balance: 4500 PLN  ← potwierdzone saldo                        │
│ cashChanges: {                                                              │
│   cc-salary-oct: { ... },                                                   │
│   cc-rent-nov: { ... },                                                     │
│   cc-adjustment-xyz: {  ← NOWA transakcja korekty                          │
│     name: "Balance Adjustment",                                             │
│     money: 500 PLN,                                                         │
│     type: INFLOW,  ← dodatnia różnica = INFLOW                             │
│     status: CONFIRMED                                                       │
│   }                                                                         │
│ }                                                                           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastStatement                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ forecasts:                                                                  │
│   2021-10: { status: IMPORTED ← zmiana z IMPORT_PENDING }                  │
│   2021-11: { status: IMPORTED }                                            │
│   2021-12: { status: IMPORTED }                                            │
│   2022-01: {                                                                │
│     status: ACTIVE,                                                         │
│     inflows: [{                                                             │
│       category: "Uncategorized",                                            │
│       transactions: [{ name: "Balance Adjustment", 500 PLN, PAID }]        │
│     }]  ← korekta dodana do ACTIVE miesiąca                                │
│   }                                                                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

##### Scenariusz C: Negatywna różnica salda

Jeśli confirmedBalance < calculatedBalance (np. 3500 PLN vs 4000 PLN):

```json
{
  "difference": { "amount": -500, "currency": "PLN" },
  "adjustmentCreated": true,
  "adjustmentCashChangeId": "cc-adjustment-xyz"
}
```

Korekta zostanie utworzona jako **OUTFLOW** o wartości 500 PLN.

---

#### Krok 4: Rollback (opcjonalny)

Jeśli użytkownik chce zresetować import przed attestacją:

**Request:**
```http
POST /api/v1/cash-flow/cf-abc-123/rollback-import
Content-Type: application/json

{
  "deleteCategories": false
}
```

**Response:**
```json
{
  "cashFlowId": "cf-abc-123",
  "deletedTransactionsCount": 2,
  "deletedCategoriesCount": 0,
  "categoriesDeleted": false,
  "status": "SETUP"
}
```

**Stan po rollback:**
- Wszystkie cashChanges usunięte
- Status pozostaje SETUP
- Miesiące wracają do pustego stanu IMPORT_PENDING
- Można importować od nowa

---

## Bank Data Ingestion (Import CSV)

### Przegląd

Bank Data Ingestion to alternatywna metoda importu historycznych transakcji poprzez plik CSV.
Zamiast ręcznego dodawania transakcji pojedynczo (endpoint `/import-historical`), można zaimportować
cały wyciąg bankowy jednym plikiem.

### Diagram wysokopoziomowy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    BANK DATA INGESTION FLOW                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. CREATE           2. UPLOAD           3. CONFIGURE        4. IMPORT     │
│  ───────────────     ──────────────      ────────────────    ──────────    │
│                                                                             │
│  createCashFlow      POST /upload        POST /mappings      POST /import  │
│  WithHistory         (plik CSV)          POST /revalidate                  │
│                                          (w pętli)                         │
│                                                                             │
│  Status: SETUP       Staging Session     Category Mappings   Bulk Import   │
│                      utworzona           skonfigurowane      N transakcji  │
│                                                                             │
│                      [status:]           [status:]           [status:]     │
│                      HAS_UNMAPPED_       READY_FOR_          COMPLETED     │
│                      CATEGORIES          IMPORT                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Porównanie metod importu

| Aspekt | Manual Import (`/import-historical`) | CSV Import (Bank Data Ingestion) |
|--------|--------------------------------------|----------------------------------|
| Liczba transakcji | Pojedyncze, N requestów | Bulk, 1 plik CSV |
| Kategorie | Muszą istnieć przed importem | Tworzone automatycznie przez mappingi |
| Walidacja | Natychmiastowa | Staging → Preview → Import |
| Rollback | Usuwa wszystkie transakcje | Usuwa transakcje z danego importu |
| Use case | Mało transakcji, ręczne wprowadzanie | Eksport z banku, wiele transakcji |

### Statusy walidacji transakcji (Staging)

| Status | Opis | Akcja wymagana |
|--------|------|----------------|
| `PENDING_MAPPING` | Brak skonfigurowanego mappingu dla kategorii bankowej | Skonfiguruj mapping |
| `VALID` | Transakcja gotowa do importu | Brak |
| `INVALID` | Błąd walidacji (np. zła data) | Popraw dane lub usuń |
| `DUPLICATE` | Transakcja już istnieje (ten sam `bankTransactionId`) | Brak (zostanie pominięta) |

### Statusy Staging Session

| Status | Opis | Następny krok |
|--------|------|---------------|
| `HAS_UNMAPPED_CATEGORIES` | Są kategorie bez mappingu | `/mappings` → `/revalidate` |
| `READY_FOR_IMPORT` | Wszystkie transakcje VALID | `/import` |
| `IMPORT_IN_PROGRESS` | Import w toku | Czekaj |
| `IMPORT_COMPLETED` | Import zakończony | Gotowe |

---

### Pętla mapowania

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PĘTLA MAPOWANIA                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. POST /upload                                                            │
│     └─► HAS_UNMAPPED_CATEGORIES (6 kategorii bez mappingu)                 │
│                                                                             │
│          ┌──────────────────────────────────────────┐                      │
│          │  POWTARZAJ AŻ WSZYSTKO ZMAPOWANE:        │                      │
│          │                                          │                      │
│     2. ──┤  POST /mappings (mapuj część lub wszystko)                      │
│          │           │                              │                      │
│     3. ──┤  POST /revalidate                        │                      │
│          │           │                              │                      │
│          │     Sprawdź response:                    │                      │
│          │     - stillUnmappedCategories: []  ──────┼──► GOTOWE            │
│          │     - stillUnmappedCategories: [..]  ────┼──► WRÓĆ DO 2.        │
│          │                                          │                      │
│          └──────────────────────────────────────────┘                      │
│                                                                             │
│  4. POST /import (gdy wszystkie VALID)                                     │
│     └─► COMPLETED (N transakcji zaimportowanych)                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Kluczowe:**
- Endpoint `/revalidate` pozwala przewalidować transakcje **bez ponownego uploadu CSV**
- Można mapować kategorie iteracyjnie (część na raz)
- Response z `/revalidate` informuje co jeszcze wymaga mappingu

---

### User Journey - Import CSV

#### Krok 1: Utworzenie CashFlow z historią

(Identyczne jak w sekcji "User Journey z przykładowymi requestami")

```http
POST /cash-flow/with-history
Content-Type: application/json

{
  "name": "Konto ING - Import CSV",
  "description": "Import z wyciągu bankowego",
  "bankAccount": {
    "number": "PL12345678901234567890123456",
    "denomination": "PLN"
  },
  "startPeriod": "2025-07",
  "initialBalance": { "amount": 10000, "currency": "PLN" }
}
```

**Response:**
```json
"cf-csv-123"
```

---

#### Krok 2: Upload pliku CSV

**Format pliku CSV:**
```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,paidDate
TXN-2025-07-001,July Salary,Monthly salary,Wpływy regularne,5000.00,PLN,INFLOW,2025-07-15
TXN-2025-07-002,July Rent,Monthly rent,Mieszkanie,1500.00,PLN,OUTFLOW,2025-07-15
TXN-2025-08-001,August Salary,Monthly salary,Wpływy regularne,5000.00,PLN,INFLOW,2025-08-15
```

**Request:**
```http
POST /api/v1/bank-data-ingestion/{cashFlowId}/upload
Content-Type: multipart/form-data

file: @historical-transactions.csv
```

**Response:**
```json
{
  "parseSummary": {
    "totalRows": 23,
    "successfulRows": 23,
    "failedRows": 0,
    "errors": []
  },
  "stagingResult": {
    "stagingSessionId": "staging-abc-123",
    "status": "HAS_UNMAPPED_CATEGORIES",
    "summary": {
      "totalTransactions": 23,
      "validTransactions": 0,
      "invalidTransactions": 0,
      "duplicateTransactions": 0
    },
    "unmappedCategories": [
      {"bankCategory": "Wpływy regularne", "count": 8, "type": "INFLOW"},
      {"bankCategory": "Mieszkanie", "count": 6, "type": "OUTFLOW"},
      {"bankCategory": "Zakupy kartą", "count": 4, "type": "OUTFLOW"},
      {"bankCategory": "Rachunki", "count": 2, "type": "OUTFLOW"},
      {"bankCategory": "Rozrywka", "count": 2, "type": "OUTFLOW"},
      {"bankCategory": "Transport", "count": 1, "type": "OUTFLOW"}
    ]
  }
}
```

**Stan po uploadzie:**
- Staging session utworzona i **persystowana**
- Transakcje zapisane ze statusem `PENDING_MAPPING`
- Lista niezmapowanych kategorii bankowych zwrócona w response

---

#### Krok 3: Konfiguracja mappingów kategorii

**Dostępne akcje mappingu:**

| Akcja | Opis |
|-------|------|
| `CREATE_NEW` | Utwórz nową kategorię główną |
| `CREATE_SUBCATEGORY` | Utwórz subkategorię pod istniejącą kategorią |
| `MAP_TO_UNCATEGORIZED` | Mapuj do systemowej kategorii "Uncategorized" |

**Request:**
```http
POST /api/v1/bank-data-ingestion/{cashFlowId}/mappings
Content-Type: application/json

{
  "mappings": [
    {
      "bankCategoryName": "Wpływy regularne",
      "action": "CREATE_NEW",
      "targetCategoryName": "Salary",
      "categoryType": "INFLOW"
    },
    {
      "bankCategoryName": "Mieszkanie",
      "action": "CREATE_SUBCATEGORY",
      "targetCategoryName": "Rent",
      "parentCategoryName": "Housing",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Zakupy kartą",
      "action": "CREATE_NEW",
      "targetCategoryName": "Groceries",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Rachunki",
      "action": "CREATE_SUBCATEGORY",
      "targetCategoryName": "Utilities",
      "parentCategoryName": "Housing",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Rozrywka",
      "action": "CREATE_NEW",
      "targetCategoryName": "Entertainment",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Transport",
      "action": "MAP_TO_UNCATEGORIZED",
      "categoryType": "OUTFLOW"
    }
  ]
}
```

**Response:**
```json
{
  "cashFlowId": "cf-csv-123",
  "mappingsConfigured": 6,
  "mappings": [
    {"bankCategoryName": "Wpływy regularne", "targetCategoryName": "Salary", "status": "CREATED"},
    {"bankCategoryName": "Mieszkanie", "targetCategoryName": "Rent", "parentCategoryName": "Housing", "status": "CREATED"},
    {"bankCategoryName": "Zakupy kartą", "targetCategoryName": "Groceries", "status": "CREATED"},
    {"bankCategoryName": "Rachunki", "targetCategoryName": "Utilities", "parentCategoryName": "Housing", "status": "CREATED"},
    {"bankCategoryName": "Rozrywka", "targetCategoryName": "Entertainment", "status": "CREATED"},
    {"bankCategoryName": "Transport", "targetCategoryName": "Uncategorized", "status": "MAPPED_TO_UNCATEGORIZED"}
  ]
}
```

---

#### Krok 4: Revalidate staging (przewalidowanie transakcji)

**Request:**
```http
POST /api/v1/bank-data-ingestion/{cashFlowId}/staging/{stagingSessionId}/revalidate
```

**Response:**
```json
{
  "stagingSessionId": "staging-abc-123",
  "status": "SUCCESS",
  "summary": {
    "totalTransactions": 23,
    "revalidatedCount": 23,
    "stillPendingCount": 0,
    "validCount": 23,
    "invalidCount": 0,
    "duplicateCount": 0
  },
  "stillUnmappedCategories": []
}
```

**Kluczowe pola response:**
- `stillPendingCount: 0` → wszystkie transakcje zmapowane
- `stillUnmappedCategories: []` → brak kategorii wymagających mappingu
- `validCount: 23` → wszystkie transakcje gotowe do importu

---

#### Krok 5: Podgląd staging preview

**Request:**
```http
GET /api/v1/bank-data-ingestion/{cashFlowId}/staging/{stagingSessionId}
```

**Response:**
```json
{
  "stagingSessionId": "staging-abc-123",
  "status": "READY_FOR_IMPORT",
  "summary": {
    "totalTransactions": 23,
    "validTransactions": 23,
    "invalidTransactions": 0,
    "duplicateTransactions": 0
  },
  "categoryBreakdown": [
    {"targetCategory": "Salary", "transactionCount": 8, "totalAmount": 35600.0, "type": "INFLOW"},
    {"targetCategory": "Rent", "transactionCount": 6, "totalAmount": 9000.0, "type": "OUTFLOW"},
    {"targetCategory": "Groceries", "transactionCount": 4, "totalAmount": 880.0, "type": "OUTFLOW"},
    {"targetCategory": "Utilities", "transactionCount": 2, "totalAmount": 300.0, "type": "OUTFLOW"},
    {"targetCategory": "Entertainment", "transactionCount": 2, "totalAmount": 550.0, "type": "OUTFLOW"},
    {"targetCategory": "Uncategorized", "transactionCount": 1, "totalAmount": 120.0, "type": "OUTFLOW"}
  ],
  "monthlyBreakdown": [
    {"month": "2025-07", "inflowTotal": 5000.0, "outflowTotal": 1750.0, "transactionCount": 3},
    {"month": "2025-08", "inflowTotal": 5000.0, "outflowTotal": 1830.0, "transactionCount": 4},
    {"month": "2025-09", "inflowTotal": 7000.0, "outflowTotal": 1700.0, "transactionCount": 4},
    {"month": "2025-10", "inflowTotal": 5200.0, "outflowTotal": 1900.0, "transactionCount": 4},
    {"month": "2025-11", "inflowTotal": 5200.0, "outflowTotal": 1620.0, "transactionCount": 3},
    {"month": "2025-12", "inflowTotal": 8200.0, "outflowTotal": 2050.0, "transactionCount": 5}
  ],
  "categoriesToCreate": [
    {"name": "Salary", "parent": null, "type": "INFLOW"},
    {"name": "Housing", "parent": null, "type": "OUTFLOW"},
    {"name": "Rent", "parent": "Housing", "type": "OUTFLOW"},
    {"name": "Utilities", "parent": "Housing", "type": "OUTFLOW"},
    {"name": "Groceries", "parent": null, "type": "OUTFLOW"},
    {"name": "Entertainment", "parent": null, "type": "OUTFLOW"}
  ]
}
```

---

#### Krok 6: Start importu

**Request:**
```http
POST /api/v1/bank-data-ingestion/{cashFlowId}/import
Content-Type: application/json

{
  "stagingSessionId": "staging-abc-123"
}
```

**Response:**
```json
{
  "jobId": "job-xyz-789",
  "cashFlowId": "cf-csv-123",
  "stagingSessionId": "staging-abc-123",
  "status": "COMPLETED",
  "input": {
    "totalTransactions": 23,
    "validTransactions": 23,
    "duplicateTransactions": 0,
    "categoriesToCreate": 6
  },
  "progress": {
    "percentage": 100,
    "phases": [
      {"name": "CREATING_CATEGORIES", "status": "COMPLETED", "processed": 6, "total": 6, "durationMs": 163},
      {"name": "IMPORTING_TRANSACTIONS", "status": "COMPLETED", "processed": 23, "total": 23, "durationMs": 326}
    ]
  },
  "result": {
    "categoriesCreated": ["Salary", "Housing", "Rent", "Utilities", "Groceries", "Entertainment"],
    "transactionsImported": 23,
    "transactionsFailed": 0,
    "errors": []
  },
  "canRollback": true
}
```

**Fazy importu:**
1. `CREATING_CATEGORIES` - tworzenie kategorii zdefiniowanych w mappingach
2. `IMPORTING_TRANSACTIONS` - import transakcji jako `HistoricalCashChangeImportedEvent`

---

#### Krok 7: Attestacja i przejście do OPEN

(Identyczne jak w sekcji "User Journey z przykładowymi requestami")

```http
POST /cash-flow/{cashFlowId}/attest-historical-import
Content-Type: application/json

{
  "confirmedBalance": { "amount": 34750.00, "currency": "PLN" },
  "forceAttestation": false,
  "createAdjustment": false
}
```

---

### Diagram stanów Staging Session

```
┌────────────────────────────────────────────────────────────────────────────┐
│                                                                            │
│               Staging Session State Machine                                │
│                                                                            │
│                                                                            │
│   ┌─────────────────────┐                                                 │
│   │    (nie istnieje)   │                                                 │
│   └─────────────────────┘                                                 │
│              │                                                             │
│              │ POST /upload                                                │
│              ▼                                                             │
│   ┌─────────────────────┐     POST /mappings +      ┌─────────────────┐   │
│   │  HAS_UNMAPPED_      │     POST /revalidate      │  READY_FOR_     │   │
│   │  CATEGORIES         │ ─────────────────────────▶│  IMPORT         │   │
│   └─────────────────────┘     (gdy wszystkie        └─────────────────┘   │
│              │                 zmapowane)                   │              │
│              │                                              │              │
│              │ POST /mappings                               │ POST /import │
│              │ (częściowe)                                  ▼              │
│              │                                    ┌─────────────────┐     │
│              └──────────────────────────────────▶│  IMPORT_IN_     │     │
│                      pozostaje w                 │  PROGRESS       │     │
│                      HAS_UNMAPPED_CATEGORIES     └─────────────────┘     │
│                                                          │               │
│                                                          │ (zakończony)  │
│                                                          ▼               │
│                                                 ┌─────────────────┐      │
│                                                 │  IMPORT_        │      │
│                                                 │  COMPLETED      │      │
│                                                 └─────────────────┘      │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

---

### Obsługa błędów Bank Data Ingestion

| Wyjątek | Kiedy | HTTP Status | Rozwiązanie |
|---------|-------|-------------|-------------|
| `StagingSessionNotFoundException` | Staging session nie istnieje | 404 | Sprawdź stagingSessionId |
| `StagingNotReadyForImportException` | Import gdy status != READY_FOR_IMPORT | 400 | Skonfiguruj brakujące mappingi |
| `CashFlowNotInSetupModeException` | Upload/Import gdy CashFlow nie w SETUP | 400 | CashFlow musi być w statusie SETUP |
| `InvalidCsvFormatException` | Błędny format pliku CSV | 400 | Popraw plik CSV |
| `DuplicateBankTransactionIdException` | Duplikat bankTransactionId w pliku | 400 | Usuń duplikaty z CSV |

---

### API Endpoints - Bank Data Ingestion

| Method | Endpoint | Opis |
|--------|----------|------|
| POST | `/api/v1/bank-data-ingestion/{cashFlowId}/upload` | Upload pliku CSV |
| POST | `/api/v1/bank-data-ingestion/{cashFlowId}/mappings` | Konfiguracja mappingów |
| GET | `/api/v1/bank-data-ingestion/{cashFlowId}/staging/{sid}` | Podgląd staging session |
| POST | `/api/v1/bank-data-ingestion/{cashFlowId}/staging/{sid}/revalidate` | Revalidate transakcji |
| POST | `/api/v1/bank-data-ingestion/{cashFlowId}/import` | Start importu |
| GET | `/api/v1/bank-data-ingestion/{cashFlowId}/import/{jobId}` | Status importu |

---

## Stany i przejścia

### CashFlow Status Transitions

```
┌────────────────────────────────────────────────────────────────────────────┐
│                                                                            │
│                    CashFlow Status State Machine                           │
│                                                                            │
│  ┌──────────┐    attestHistoricalImport    ┌──────────┐                   │
│  │  SETUP   │ ─────────────────────────────▶│   OPEN   │                   │
│  └──────────┘                               └──────────┘                   │
│       │                                          │                         │
│       │ createCashFlow                           │ closeCashFlow          │
│       │ WithHistory                              │ (future)               │
│       │                                          ▼                         │
│       │                                    ┌──────────┐                   │
│       │                                    │  CLOSED  │                   │
│       │                                    └──────────┘                   │
│       │                                                                    │
│       │ rollbackImport                                                    │
│       └───────────────┐                                                   │
│                       │ (pozostaje w SETUP,                               │
│                       ▼  tylko czyści dane)                               │
│                  ┌──────────┐                                             │
│                  │  SETUP   │                                             │
│                  └──────────┘                                             │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

### Monthly Forecast Status Transitions

```
┌────────────────────────────────────────────────────────────────────────────┐
│                                                                            │
│              Monthly Forecast Status State Machine                         │
│                                                                            │
│   HISTORICAL IMPORT FLOW:                                                  │
│   ───────────────────────                                                  │
│                                                                            │
│   ┌────────────────┐      attestHistoricalImport     ┌──────────┐         │
│   │ IMPORT_PENDING │ ───────────────────────────────▶│ IMPORTED │         │
│   └────────────────┘                                 └──────────┘         │
│                                                                            │
│                                                                            │
│   NORMAL MONTHLY FLOW:                                                     │
│   ────────────────────                                                     │
│                                                                            │
│   ┌────────────┐    (calendar)    ┌────────┐    attestMonth   ┌──────────┐│
│   │ FORECASTED │ ────────────────▶│ ACTIVE │ ────────────────▶│ ATTESTED ││
│   └────────────┘                  └────────┘                  └──────────┘│
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## Obsługa błędów

### Możliwe wyjątki podczas importu

| Wyjątek | Kiedy | HTTP Status | Rozwiązanie |
|---------|-------|-------------|-------------|
| `OperationNotAllowedInSetupModeException` | Próba appendCashChange w SETUP | 400 | Użyj importHistoricalCashChange |
| `ImportNotAllowedInNonSetupModeException` | Próba importu gdy status != SETUP | 400 | Można importować tylko w SETUP |
| `ImportDateOutsideSetupPeriodException` | paidDate >= activePeriod | 400 | paidDate musi być przed bieżącym miesiącem |
| `ImportDateBeforeStartPeriodException` | paidDate < startPeriod | 400 | paidDate musi być >= startPeriod |
| `ImportDateInFutureException` | paidDate > now() | 400 | paidDate nie może być w przyszłości |
| `AttestationNotAllowedInNonSetupModeException` | Attestacja gdy status != SETUP | 400 | Można attestować tylko w SETUP |
| `BalanceMismatchException` | Różnica salda bez forceAttestation i createAdjustment | 400 | Użyj forceAttestation=true lub createAdjustment=true |

### Przykład błędu

**Request z błędną datą:**
```http
POST /api/v1/cash-flow/cf-abc-123/import-historical
{
  "paidDate": "2022-02-15T12:00:00Z",  ← data w przyszłości!
  ...
}
```

**Response:**
```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Import date [2022-02-15] is in the future. Current time: [2022-01-15T10:30:00Z]",
  "instance": "/api/v1/cash-flow/cf-abc-123/import-historical"
}
```

---

## Decyzje projektowe

### Dlaczego `importCutoffDateTime`?

**Problem:** Jak odróżnić dane zaimportowane z banku od ręcznie dodanych transakcji?

**Rozwiązanie:** Zapisujemy timestamp attestacji jako granicę.

**Korzyści:**
1. Łatwe filtrowanie: `transaction.created < importCutoffDateTime` = import
2. Możliwość różnych reguł walidacji dla danych historycznych
3. Audit trail - wiemy dokładnie kiedy zakończono import

**Alternatywy rozważane:**
- Flaga `isImported` na każdej transakcji → większy overhead, trudniejsze zapytania
- Osobna kolekcja dla importów → duplikacja danych, skomplikowana synchronizacja

### Dlaczego `createAdjustment` zamiast wymuszenia?

**Problem:** Co zrobić gdy saldo się nie zgadza?

**Opcje:**
1. `forceAttestation=true` - akceptujemy różnicę, ignorujemy
2. `createAdjustment=true` - tworzymy transakcję korygującą

**Dlaczego oba?**
- `forceAttestation` - dla przypadków gdy różnica jest akceptowalna (np. zaokrąglenia)
- `createAdjustment` - dla przypadków gdy chcemy śledzić różnicę jako "brakującą" transakcję

**Korzyści `createAdjustment`:**
- Zachowana pełna historia
- Raporty są spójne
- Użytkownik widzi skąd bierze się różnica

### Dlaczego korekta trafia do ACTIVE miesiąca?

**Problem:** Gdzie umieścić transakcję korekty?

**Rozważane opcje:**
1. Ostatni miesiąc IMPORT_PENDING → już zamknięty, nie powinniśmy modyfikować
2. Osobny "miesiąc korekt" → skomplikowana struktura
3. ACTIVE miesiąc → naturalnie widoczne w bieżącym bilansie

**Decyzja:** ACTIVE miesiąc

**Uzasadnienie:**
- Korekta wpływa na bieżące saldo (które właśnie potwierdzamy)
- Użytkownik widzi ją od razu w bieżącym miesiącu
- Prosta implementacja

### Dlaczego status IMPORTED zamiast zostawienia IMPORT_PENDING?

**Problem:** Jak oznaczyć miesiące po attestacji?

**Alternatywy:**
1. Zostawić IMPORT_PENDING → nie wiadomo czy attestowano
2. Nowy status IMPORTED → jasne rozróżnienie

**Decyzja:** Nowy status IMPORTED

**Uzasadnienie:**
- Wyraźna semantyka: IMPORT_PENDING = czeka na dane, IMPORTED = dane zaimportowane
- Możliwość różnych reguł dla obu stanów
- Lepszy UX - użytkownik widzi postęp

---

## Archiwizacja kategorii

### Przegląd

Kategorie mogą być archiwizowane, aby ukryć je przed nowymi transakcjami, zachowując jednocześnie pełną historię.

### Model danych kategorii

| Pole | Typ | Opis |
|------|-----|------|
| `categoryName` | `CategoryName` | Nazwa kategorii |
| `origin` | `CategoryOrigin` | Pochodzenie: SYSTEM, IMPORTED, USER_CREATED |
| `archived` | `boolean` | Czy kategoria jest zarchiwizowana |
| `validFrom` | `ZonedDateTime` | Data początkowa ważności (nullable = od zawsze) |
| `validTo` | `ZonedDateTime` | Data końcowa ważności (ustawiana przy archiwizacji) |
| `isModifiable` | `boolean` | Czy kategoria może być modyfikowana |

### CategoryOrigin

| Wartość | Opis | Możliwe operacje |
|---------|------|------------------|
| `SYSTEM` | Kategoria systemowa (np. "Uncategorized") | Tylko odczyt - nie można archiwizować ani usuwać |
| `IMPORTED` | Kategoria zaimportowana z wyciągu bankowego | Archiwizacja, zmiana nazwy |
| `USER_CREATED` | Kategoria stworzona ręcznie przez użytkownika | Pełna kontrola - archiwizacja, zmiana nazwy, usunięcie |

### Cykl życia kategorii

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CATEGORY LIFECYCLE                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌────────────┐                                    ┌────────────────┐       │
│  │  Created   │  ────── archiveCategory ─────────▶ │   Archived     │       │
│  │            │                                    │                │       │
│  │ archived:  │                                    │ archived: true │       │
│  │   false    │  ◀──── unarchiveCategory ───────── │ validTo: now() │       │
│  │ validTo:   │                                    │                │       │
│  │   null     │                                    │                │       │
│  └────────────┘                                    └────────────────┘       │
│                                                                             │
│  Kategoria aktywna:                              Kategoria zarchiwizowana:  │
│  - Widoczna w selekcji                          - Ukryta w selekcji        │
│  - Możliwa do użycia                            - Niedostępna dla nowych   │
│    w nowych transakcjach                          transakcji               │
│                                                 - Widoczna w historii      │
│                                                 - Można przywrócić         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Przykłady API

#### Tworzenie kategorii użytkownika

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category
Content-Type: application/json

{
  "category": "Groceries",
  "type": "OUTFLOW"
}
```

Nowa kategoria ma domyślnie:
- `origin: USER_CREATED`
- `archived: false`
- `validFrom: null` (ważna od zawsze)
- `validTo: null` (ważna bezterminowo)

#### Archiwizacja kategorii

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category/archive
Content-Type: application/json

{
  "categoryName": "Groceries",
  "categoryType": "OUTFLOW"
}
```

Po archiwizacji:
- `archived: true`
- `validTo: <timestamp archiwizacji>`

#### Próba archiwizacji kategorii systemowej

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category/archive
Content-Type: application/json

{
  "categoryName": "Uncategorized",
  "categoryType": "OUTFLOW"
}
```

**Response (400 Bad Request):**
```json
{
  "type": "CannotArchiveSystemCategoryException",
  "message": "Cannot archive system category: Uncategorized"
}
```

#### Przywracanie kategorii (unarchive)

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category/unarchive
Content-Type: application/json

{
  "categoryName": "Groceries",
  "categoryType": "OUTFLOW"
}
```

Po przywróceniu:
- `archived: false`
- `validTo: null`

### Zachowanie historycznych transakcji

Zarchiwizowane kategorie pozostają widoczne w transakcjach, które ich używają:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Transakcja z zarchiwizowaną kategorią                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│ cashChangeId: cc-123                                                        │
│ name: "Weekly groceries"                                                    │
│ categoryName: "Groceries"  ← kategoria zarchiwizowana, ale nadal widoczna  │
│ money: 150 PLN                                                              │
│ type: OUTFLOW                                                               │
│ status: CONFIRMED                                                           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ Lista kategorii OUTFLOW                                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│ - Uncategorized (origin: SYSTEM, archived: false)                           │
│ - Groceries (origin: USER_CREATED, archived: true)  ← widoczna, ale ukryta │
│   w selekcji przy tworzeniu nowych transakcji                               │
│ - Rent (origin: USER_CREATED, archived: false)                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Filtrowanie kategorii w UI

**Kategorie do wyboru (nowe transakcje):**
```java
categories.stream()
    .filter(c -> !c.isArchived())
    .collect(toList());
```

**Wszystkie kategorie (raporty, historia):**
```java
categories  // wszystkie, włącznie z zarchiwizowanymi
```

### Walidacja dat (validFrom/validTo)

Metoda `isValidAt(ZonedDateTime date)` sprawdza czy kategoria jest ważna dla danej daty:

```java
public boolean isValidAt(ZonedDateTime date) {
    if (archived) return false;
    if (validFrom != null && date.isBefore(validFrom)) return false;
    if (validTo != null && date.isAfter(validTo)) return false;
    return true;
}
```

**Przypadki użycia:**
- Filtrowanie kategorii dostępnych dla konkretnej daty transakcji
- Walidacja podczas edycji starych transakcji
- Raporty uwzględniające historyczne struktury kategorii

### Procesowanie eventów w CashFlowForecastProcessor

Zdarzenia archiwizacji kategorii są obsługiwane przez dedykowane handlery:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Event Flow dla archiwizacji kategorii                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  CashFlow.apply(CategoryArchivedEvent)                                      │
│            ↓                                                                │
│  CashFlowEventEmitter → Kafka (cash_flow topic)                            │
│            ↓                                                                │
│  CashFlowEventListener → CashFlowForecastProcessor                         │
│            ↓                                                                │
│  CategoryArchivedEventHandler.handle()                                      │
│     │                                                                       │
│     ├─→ Update CategoryNode in categoryStructure                           │
│     │      - archived = true                                               │
│     │      - validTo = archivedAt                                          │
│     │                                                                       │
│     └─→ Update CashCategory in all monthly forecasts                       │
│            - archived = true                                               │
│            - validTo = archivedAt                                          │
│            ↓                                                                │
│  CashFlowForecastStatementRepository.save()                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### JSON Response - kategorie z metadanymi archiwizacji

```json
{
  "categoryStructure": {
    "inflowCategoryStructure": [
      {
        "categoryName": "Salary",
        "nodes": [],
        "archived": false,
        "validFrom": null,
        "validTo": null,
        "origin": "USER_CREATED"
      },
      {
        "categoryName": "Royalties",
        "nodes": [],
        "archived": true,
        "validFrom": null,
        "validTo": "2024-06-15T10:30:00Z",
        "origin": "IMPORTED"
      }
    ],
    "outflowCategoryStructure": [
      {
        "categoryName": "Uncategorized",
        "nodes": [],
        "archived": false,
        "validFrom": null,
        "validTo": null,
        "origin": "SYSTEM"
      },
      {
        "categoryName": "Netflix",
        "nodes": [],
        "archived": true,
        "validFrom": "2023-01-01T00:00:00Z",
        "validTo": "2024-03-01T12:00:00Z",
        "origin": "USER_CREATED"
      }
    ]
  }
}
```

### Implementacja UI - toggle dla zarchiwizowanych kategorii

```typescript
// Komponent React - przykład
interface CategoryDropdownProps {
  categories: CashCategory[];
  showArchived: boolean;
  onToggleArchived: () => void;
}

const CategoryDropdown = ({ categories, showArchived, onToggleArchived }) => {
  const filteredCategories = showArchived
    ? categories
    : categories.filter(c => !c.archived);

  return (
    <div>
      <label>
        <input
          type="checkbox"
          checked={showArchived}
          onChange={onToggleArchived}
        />
        Pokaż zarchiwizowane
      </label>

      <select>
        {filteredCategories.map(category => (
          <option
            key={category.categoryName}
            disabled={category.archived}
            className={category.archived ? 'archived' : ''}
          >
            {category.categoryName}
            {category.archived && ' (zarchiwizowana)'}
          </option>
        ))}
      </select>
    </div>
  );
};
```

---

## Wersjonowanie kategorii (Category Versioning)

### Przegląd

Wersjonowanie kategorii pozwala na zachowanie pełnej historii zmian kategorii w czasie. Gdy kategoria jest archiwizowana, można utworzyć nową kategorię o tej samej nazwie - obie będą współistnieć w systemie, ale tylko jedna będzie aktywna.

### Scenariusz: Zmiana dostawcy usługi streamingowej

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CATEGORY VERSIONING TIMELINE                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐                    ┌─────────────┐                        │
│  │  Streaming  │                    │  Streaming  │                        │
│  │   v1 (Old)  │    Archive         │   v2 (New)  │                        │
│  │             │  ──────────────▶   │             │                        │
│  │  Netflix    │    Jan 2024        │  Disney+    │                        │
│  │  $15/month  │                    │  $12/month  │                        │
│  └─────────────┘                    └─────────────┘                        │
│                                                                             │
│  Jan 2023                           Jan 2024            Present            │
│  ─────────────────────────────────────────────────────────────────────▶    │
│                                                                             │
│  Transactions in v1:                Transactions in v2:                    │
│  - Netflix Jan 2023                 - Disney+ Feb 2024                     │
│  - Netflix Feb 2023                 - Disney+ Mar 2024                     │
│  - ...                              - ...                                  │
│  - Netflix Dec 2023                                                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### User Journey - Wersjonowanie kategorii

#### Krok 1: Kategoria Streaming z subskrypcją Netflix

**Stan początkowy:**
```json
{
  "categoryStructure": {
    "outflowCategoryStructure": [
      {
        "categoryName": "Streaming",
        "archived": false,
        "validFrom": "2023-01-01T00:00:00Z",
        "validTo": null,
        "origin": "USER_CREATED",
        "nodes": []
      }
    ]
  }
}
```

**Transakcje w kategorii Streaming (2023):**
```
- Netflix January:  $15.00 (2023-01-15)
- Netflix February: $15.00 (2023-02-15)
- Netflix March:    $15.00 (2023-03-15)
...
- Netflix December: $15.00 (2023-12-15)
```

#### Krok 2: Archiwizacja kategorii Streaming

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category/archive
Content-Type: application/json

{
  "categoryName": "Streaming",
  "categoryType": "OUTFLOW",
  "forceArchiveChildren": false
}
```

**Stan po archiwizacji:**
```json
{
  "categoryStructure": {
    "outflowCategoryStructure": [
      {
        "categoryName": "Streaming",
        "archived": true,
        "validFrom": "2023-01-01T00:00:00Z",
        "validTo": "2024-01-15T10:00:00Z",
        "origin": "USER_CREATED",
        "nodes": []
      }
    ]
  }
}
```

#### Krok 3: Utworzenie nowej wersji kategorii Streaming

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category
Content-Type: application/json

{
  "category": "Streaming",
  "type": "OUTFLOW"
}
```

**Stan po utworzeniu nowej wersji:**
```json
{
  "categoryStructure": {
    "outflowCategoryStructure": [
      {
        "categoryName": "Streaming",
        "archived": true,
        "validFrom": "2023-01-01T00:00:00Z",
        "validTo": "2024-01-15T10:00:00Z",
        "origin": "USER_CREATED",
        "nodes": []
      },
      {
        "categoryName": "Streaming",
        "archived": false,
        "validFrom": "2024-01-15T10:00:00Z",
        "validTo": null,
        "origin": "USER_CREATED",
        "nodes": []
      }
    ]
  }
}
```

**Teraz mamy dwie kategorie "Streaming":**
- v1 (archived): validFrom=2023-01-01, validTo=2024-01-15
- v2 (active): validFrom=2024-01-15, validTo=null

#### Krok 4: Dodawanie transakcji do nowej wersji

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/append-paid
Content-Type: application/json

{
  "name": "Disney+ February",
  "description": "Monthly Disney+ subscription",
  "money": { "amount": 12.00, "currency": "USD" },
  "type": "OUTFLOW",
  "category": "Streaming",
  "dueDate": "2024-02-15T10:00:00Z",
  "paidDate": "2024-02-15T10:00:00Z"
}
```

System automatycznie przypisze transakcję do **aktywnej** wersji kategorii Streaming (v2).

### Stan CashFlow i Forecasts po wersjonowaniu

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastStatement                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│ MONTHLY FORECASTS:                                                          │
│                                                                             │
│ 2023-12 (IMPORTED):                                                         │
│   OUTFLOWS:                                                                 │
│     - Streaming (v1): Netflix December $15.00 [PAID]                       │
│                                                                             │
│ 2024-01 (IMPORTED):                                                         │
│   OUTFLOWS:                                                                 │
│     - Streaming (v1): Netflix January $15.00 [PAID]                        │
│       ↑ Transakcja przypisana do v1 (przed archiwizacją)                   │
│                                                                             │
│ 2024-02 (ACTIVE):                                                           │
│   OUTFLOWS:                                                                 │
│     - Streaming (v2): Disney+ February $12.00 [PAID]                       │
│       ↑ Transakcja przypisana do v2 (po utworzeniu nowej wersji)           │
│                                                                             │
│ CATEGORY STRUCTURE:                                                         │
│   OUTFLOW:                                                                  │
│     └── Streaming (v1) [ARCHIVED] - 12 transactions from 2023              │
│     └── Streaming (v2) [ACTIVE]   - transactions from 2024+                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Obsługa wersjonowanych kategorii w handlerach

System automatycznie znajduje właściwą wersję kategorii:

```java
// AppendPaidCashChangeCommandHandler.java
Category activeCategory = findActiveCategory(categories, command.categoryName());
if (activeCategory == null) {
    Category archivedCategory = findArchivedCategory(categories, command.categoryName());
    if (archivedCategory != null) {
        throw new CategoryIsArchivedException(command.categoryName());
    }
}
```

**Logika:**
1. Najpierw szukamy aktywnej kategorii o podanej nazwie
2. Jeśli nie ma aktywnej, sprawdzamy czy istnieje zarchiwizowana
3. Jeśli jest tylko zarchiwizowana → błąd `CategoryIsArchivedException`
4. Jeśli jest aktywna → używamy jej do transakcji

---

## Archiwizacja z subkategoriami (forceArchiveChildren)

### Przegląd

Parametr `forceArchiveChildren` kontroluje zachowanie subkategorii podczas archiwizacji kategorii nadrzędnej:

| Wartość | Zachowanie |
|---------|------------|
| `true`  | Archiwizuje kategorię nadrzędną ORAZ wszystkie jej subkategorie |
| `false` | Archiwizuje TYLKO kategorię nadrzędną, subkategorie pozostają aktywne |

### Scenariusz A: forceArchiveChildren = true

**Przypadek użycia:** Całkowite wycofanie działu (np. zamknięcie działu marketingu)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    forceArchiveChildren = TRUE                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  PRZED ARCHIWIZACJĄ:                     PO ARCHIWIZACJI:                   │
│                                                                             │
│  Marketing [ACTIVE]                      Marketing [ARCHIVED]               │
│    ├── Digital Ads [ACTIVE]                ├── Digital Ads [ARCHIVED]       │
│    │     └── Google Ads                    │     └── Google Ads             │
│    │     └── Facebook Ads                  │     └── Facebook Ads           │
│    └── Print Ads [ACTIVE]                  └── Print Ads [ARCHIVED]         │
│          └── Magazines                           └── Magazines              │
│          └── Newspapers                          └── Newspapers             │
│                                                                             │
│  Wszystkie transakcje pozostają, ale żadna z tych kategorii               │
│  nie jest dostępna dla nowych transakcji.                                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### User Journey - forceArchiveChildren = true

**Krok 1: Stan początkowy z transakcjami**

```json
{
  "categoryStructure": {
    "outflowCategoryStructure": [
      {
        "categoryName": "Marketing",
        "archived": false,
        "nodes": [
          {
            "categoryName": "Digital Ads",
            "archived": false,
            "nodes": []
          },
          {
            "categoryName": "Print Ads",
            "archived": false,
            "nodes": []
          }
        ]
      }
    ]
  }
}
```

**Transakcje:**
```
- Marketing: Campaign Planning $500 (paid)
- Digital Ads: Google Ads January $200 (paid)
- Digital Ads: Facebook Ads January $150 (paid)
- Print Ads: Magazine Ad $300 (paid)
```

**Krok 2: Archiwizacja z forceArchiveChildren=true**

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category/archive
Content-Type: application/json

{
  "categoryName": "Marketing",
  "categoryType": "OUTFLOW",
  "forceArchiveChildren": true
}
```

**Response:**
```json
{
  "archivedCategories": ["Marketing", "Digital Ads", "Print Ads"],
  "totalArchived": 3
}
```

**Krok 3: Stan po archiwizacji**

```json
{
  "categoryStructure": {
    "outflowCategoryStructure": [
      {
        "categoryName": "Marketing",
        "archived": true,
        "validTo": "2024-06-01T12:00:00Z",
        "nodes": [
          {
            "categoryName": "Digital Ads",
            "archived": true,
            "validTo": "2024-06-01T12:00:00Z",
            "nodes": []
          },
          {
            "categoryName": "Print Ads",
            "archived": true,
            "validTo": "2024-06-01T12:00:00Z",
            "nodes": []
          }
        ]
      }
    ]
  }
}
```

**Próba dodania transakcji do Digital Ads:**
```http
POST /api/v1/cash-flow/{cashFlowId}/append-paid
{
  "category": "Digital Ads",
  ...
}
```

**Response (400 Bad Request):**
```json
{
  "error": "CategoryIsArchivedException",
  "message": "Category [Digital Ads] is archived and cannot accept new transactions"
}
```

---

### Scenariusz B: forceArchiveChildren = false

**Przypadek użycia:** Reorganizacja struktury (np. usunięcie kategorii pośredniej, ale zachowanie szczegółowych)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    forceArchiveChildren = FALSE                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  PRZED ARCHIWIZACJĄ:                     PO ARCHIWIZACJI:                   │
│                                                                             │
│  Transportation [ACTIVE]                 Transportation [ARCHIVED]          │
│    ├── Fuel [ACTIVE]                       ├── Fuel [ACTIVE] ← nadal       │
│    └── Parking [ACTIVE]                    │     aktywne!                  │
│                                            └── Parking [ACTIVE]            │
│                                                                             │
│  Kategoria nadrzędna zarchiwizowana, ale subkategorie pozostają           │
│  aktywne i mogą przyjmować nowe transakcje.                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### User Journey - forceArchiveChildren = false

**Krok 1: Stan początkowy z transakcjami**

```json
{
  "categoryStructure": {
    "outflowCategoryStructure": [
      {
        "categoryName": "Transportation",
        "archived": false,
        "nodes": [
          {
            "categoryName": "Fuel",
            "archived": false,
            "nodes": []
          },
          {
            "categoryName": "Parking",
            "archived": false,
            "nodes": []
          }
        ]
      }
    ]
  }
}
```

**Transakcje:**
```
- Fuel: Gas Station January $80 (paid)
- Parking: Downtown Parking $25 (paid)
```

**Krok 2: Archiwizacja z forceArchiveChildren=false**

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/category/archive
Content-Type: application/json

{
  "categoryName": "Transportation",
  "categoryType": "OUTFLOW",
  "forceArchiveChildren": false
}
```

**Response:**
```json
{
  "archivedCategories": ["Transportation"],
  "totalArchived": 1,
  "activeChildren": ["Fuel", "Parking"]
}
```

**Krok 3: Stan po archiwizacji**

```json
{
  "categoryStructure": {
    "outflowCategoryStructure": [
      {
        "categoryName": "Transportation",
        "archived": true,
        "validTo": "2024-06-01T12:00:00Z",
        "nodes": [
          {
            "categoryName": "Fuel",
            "archived": false,
            "validTo": null,
            "nodes": []
          },
          {
            "categoryName": "Parking",
            "archived": false,
            "validTo": null,
            "nodes": []
          }
        ]
      }
    ]
  }
}
```

**Krok 4: Dodanie nowej transakcji do aktywnej subkategorii**

**Request:**
```http
POST /api/v1/cash-flow/{cashFlowId}/append-paid
Content-Type: application/json

{
  "name": "Highway toll",
  "description": "A4 highway toll",
  "money": { "amount": 15.00, "currency": "PLN" },
  "type": "OUTFLOW",
  "category": "Fuel",
  "dueDate": "2024-06-15T10:00:00Z",
  "paidDate": "2024-06-15T10:00:00Z"
}
```

**Response:**
```json
"cc-toll-june"
```

Transakcja została pomyślnie dodana do kategorii "Fuel", mimo że kategoria nadrzędna "Transportation" jest zarchiwizowana.

---

### Porównanie scenariuszy

| Aspekt | forceArchiveChildren=true | forceArchiveChildren=false |
|--------|--------------------------|----------------------------|
| **Kategoria nadrzędna** | Zarchiwizowana | Zarchiwizowana |
| **Subkategorie** | Wszystkie zarchiwizowane | Pozostają aktywne |
| **Nowe transakcje** | Niedozwolone nigdzie | Dozwolone w subkategoriach |
| **Przypadek użycia** | Całkowite wycofanie | Reorganizacja struktury |
| **Zachowanie historii** | Pełne | Pełne |

### Stan CashFlow i Forecasts - podsumowanie

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ CashFlowForecastStatement - after category operations                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│ CATEGORY STRUCTURE SUMMARY:                                                 │
│                                                                             │
│ OUTFLOW CATEGORIES:                                                         │
│                                                                             │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ Versioned Category Example:                                             │ │
│ │                                                                         │ │
│ │   Streaming (v1) [ARCHIVED]                                            │ │
│ │     - validFrom: 2023-01-01                                            │ │
│ │     - validTo: 2024-01-15                                              │ │
│ │     - transactions: 12 (Netflix 2023)                                  │ │
│ │                                                                         │ │
│ │   Streaming (v2) [ACTIVE]                                              │ │
│ │     - validFrom: 2024-01-15                                            │ │
│ │     - validTo: null                                                    │ │
│ │     - transactions: 5 (Disney+ 2024)                                   │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ Force Archive Children = TRUE:                                          │ │
│ │                                                                         │ │
│ │   Marketing [ARCHIVED]                                                  │ │
│ │     ├── Digital Ads [ARCHIVED]                                         │ │
│ │     │     - transactions: 5 (all historical)                           │ │
│ │     └── Print Ads [ARCHIVED]                                           │ │
│ │           - transactions: 3 (all historical)                           │ │
│ │                                                                         │ │
│ │   ⚠ No new transactions allowed in any of these categories            │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ Force Archive Children = FALSE:                                         │ │
│ │                                                                         │ │
│ │   Transportation [ARCHIVED]                                             │ │
│ │     ├── Fuel [ACTIVE]                                                  │ │
│ │     │     - transactions: 8 (can add more!)                            │ │
│ │     └── Parking [ACTIVE]                                               │ │
│ │           - transactions: 4 (can add more!)                            │ │
│ │                                                                         │ │
│ │   ✓ New transactions allowed in Fuel and Parking                       │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Changelog

| Data | Zmiany |
|------|--------|
| 2026-01-07 | Utworzenie dokumentu |
| 2026-01-07 | Dodanie sekcji o createAdjustment i importCutoffDateTime |
| 2026-01-07 | Dodanie sekcji o archiwizacji kategorii (VID-92) |
| 2026-01-07 | Dodanie procesowania eventów archiwizacji w ForecastProcessor |
| 2026-01-07 | Dodanie sekcji o wersjonowaniu kategorii (VID-90) |
| 2026-01-07 | Dodanie sekcji o forceArchiveChildren z przykładami TRUE/FALSE |
| 2026-01-07 | Dodanie diagramów stanu CashFlow i Forecasts po operacjach na kategoriach |
| 2026-01-25 | Dodanie sekcji "Bank Data Ingestion (Import CSV)" z pełnym workflow |
| 2026-01-25 | Dodanie diagramu "Pętla mapowania" dla iteracyjnego procesu mappingów |
| 2026-01-25 | Dodanie opisu endpointu `/revalidate` i statusów staging session |
| 2026-01-25 | Dodanie tabeli porównawczej: Manual Import vs CSV Import |
