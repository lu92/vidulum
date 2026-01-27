# Bank Data Ingestion Pipeline - Dokumentacja Techniczna

**Data aktualizacji:** 2026-01-28

## Spis treści

1. [Przegląd](#przegląd)
2. [Architektura](#architektura)
3. [MongoDB Collections](#mongodb-collections)
4. [REST API](#rest-api)
5. [Upload CSV](#upload-csv)
6. [User Journey](#user-journey)
7. [Konfiguracja](#konfiguracja)
8. [Rollback Mechanism](#rollback-mechanism)
9. [Przyszłość - Mikroserwis](#przyszłość---mikroserwis)

---

## Przegląd

Bank Data Ingestion Pipeline to moduł odpowiedzialny za bezpieczny, etapowy import danych z banków (CSV/API) do systemu CashFlow.

### Kluczowe cechy

- **Staged import** - dane są walidowane i przetwarzane przed zapisem do CashFlow
- **Preview przed importem** - user widzi dokładne podsumowanie przed zatwierdzeniem
- **Progress tracking** - śledzenie postępu importu w czasie rzeczywistym
- **Rollback** - możliwość cofnięcia importu przed attestacją CashFlow
- **Historia importów** - pełna historia co, kiedy i ile zostało zaimportowane
- **Upload CSV** - automatyczne parsowanie i staging transakcji z plików CSV

### Etapy pipeline'u

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    STAGED IMPORT PIPELINE                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  STAGE 1          STAGE 2           STAGE 3          STAGE 4               │
│  ────────         ────────          ────────         ────────              │
│  PARSE            MAP               STAGE            IMPORT                │
│                                                                             │
│  CSV/API  ───▶  Configure    ───▶  Staging     ───▶  CashFlow             │
│  Bank data      Mappings          Collection        Aggregate              │
│                                                                             │
│  [upload/       [category_        [staged_          [cashflow +            │
│   staging]       mappings]         transactions]     import_jobs]          │
│                                                                             │
│  Temporary      Reusable          Temporary         Permanent              │
│  (parsed)       (kept for         (TTL: 24h)        (history)              │
│                  future imports)                                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Architektura

### Struktura modułu

```
com.multi.vidulum.bank_data_ingestion/
├── domain/
│   ├── CategoryMapping.java           # Value object - mapping config
│   ├── StagedTransaction.java         # Value object - staged transaction
│   ├── ImportJob.java                 # Aggregate - import job with progress
│   ├── StagingSession.java            # Value object - staging session info
│   ├── BankCsvRow.java                # Record - znormalizowany wiersz CSV
│   └── enums/
│       ├── MappingAction.java         # CREATE_NEW, CREATE_SUBCATEGORY, MAP_TO_UNCATEGORIZED
│       ├── ImportJobStatus.java       # PENDING, PROCESSING, COMPLETED, etc.
│       ├── ImportPhase.java           # CREATING_CATEGORIES, IMPORTING_TRANSACTIONS
│       └── ValidationStatus.java      # VALID, INVALID, DUPLICATE, PENDING_MAPPING
│
├── app/
│   ├── commands/
│   │   ├── configure_mapping/
│   │   │   ├── ConfigureCategoryMappingCommand.java
│   │   │   └── ConfigureCategoryMappingCommandHandler.java
│   │   ├── delete_mapping/
│   │   │   ├── DeleteCategoryMappingCommand.java
│   │   │   └── DeleteCategoryMappingCommandHandler.java
│   │   ├── delete_all_mappings/
│   │   │   ├── DeleteAllCategoryMappingsCommand.java
│   │   │   └── DeleteAllCategoryMappingsCommandHandler.java
│   │   ├── stage_transactions/
│   │   │   ├── StageTransactionsCommand.java
│   │   │   └── StageTransactionsCommandHandler.java
│   │   ├── delete_staging_session/
│   │   │   ├── DeleteStagingSessionCommand.java
│   │   │   └── DeleteStagingSessionCommandHandler.java
│   │   ├── revalidate_staging/
│   │   │   ├── RevalidateStagingCommand.java
│   │   │   └── RevalidateStagingCommandHandler.java
│   │   ├── upload_csv/
│   │   │   ├── UploadCsvCommand.java
│   │   │   └── UploadCsvCommandHandler.java
│   │   ├── start_import/
│   │   │   ├── StartImportJobCommand.java
│   │   │   └── StartImportJobCommandHandler.java
│   │   ├── rollback_import/
│   │   │   ├── RollbackImportJobCommand.java
│   │   │   └── RollbackImportJobCommandHandler.java
│   │   └── finalize_import/
│   │       ├── FinalizeImportJobCommand.java
│   │       └── FinalizeImportJobCommandHandler.java
│   │
│   ├── queries/
│   │   ├── get_mappings/
│   │   │   ├── GetCategoryMappingsQuery.java
│   │   │   └── GetCategoryMappingsQueryHandler.java
│   │   ├── get_staging_preview/
│   │   │   ├── GetStagingPreviewQuery.java
│   │   │   └── GetStagingPreviewQueryHandler.java
│   │   ├── list_staging_sessions/
│   │   │   ├── ListStagingSessionsQuery.java
│   │   │   └── ListStagingSessionsQueryHandler.java
│   │   ├── get_import_progress/
│   │   │   ├── GetImportProgressQuery.java
│   │   │   └── GetImportProgressQueryHandler.java
│   │   └── list_import_jobs/
│   │       ├── ListImportJobsQuery.java
│   │       └── ListImportJobsQueryHandler.java
│   │
│   ├── CsvParserService.java          # Parser CSV → BankCsvRow
│   ├── BankDataIngestionRestController.java
│   └── BankDataIngestionDto.java      # Wszystkie DTO
│
└── infrastructure/
    ├── CategoryMappingMongoRepository.java
    ├── StagedTransactionMongoRepository.java
    ├── ImportJobMongoRepository.java
    └── entities/
        ├── CategoryMappingEntity.java
        ├── StagedTransactionEntity.java
        └── ImportJobEntity.java
```

### Zależności między modułami

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    MODULE DEPENDENCIES                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  bank_data_ingestion                                                        │
│         │                                                                   │
│         │ uses (import transactions)                                        │
│         ▼                                                                   │
│     cashflow (aggregate)                                                    │
│         │                                                                   │
│         │ emits events                                                      │
│         ▼                                                                   │
│  cashflow_forecast_processor (updates read model)                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## MongoDB Collections

### 1. category_mappings

Przechowuje konfigurację mapowań kategorii bankowych na kategorie systemowe.

**Collection name:** `category_mappings`

**Document structure:**

```json
{
  "_id": "ObjectId",
  "mappingId": "UUID (String)",
  "cashFlowId": "UUID (String)",
  "bankCategoryName": "String",
  "targetCategoryName": "String",
  "parentCategoryName": "String | null",
  "categoryType": "INFLOW | OUTFLOW",
  "action": "CREATE_NEW | CREATE_SUBCATEGORY | MAP_TO_UNCATEGORIZED",
  "createdAt": "ISODate",
  "updatedAt": "ISODate"
}
```

**Indexes:**

```javascript
// Unique constraint - one mapping per (cashFlowId, bankCategoryName, categoryType)
db.category_mappings.createIndex(
  { "cashFlowId": 1, "bankCategoryName": 1, "categoryType": 1 },
  { unique: true }
)

// Query by cashFlowId
db.category_mappings.createIndex({ "cashFlowId": 1 })
```

**MappingAction - dostępne akcje:**

| Akcja | Opis |
|-------|------|
| `CREATE_NEW` | Tworzy nową kategorię główną |
| `CREATE_SUBCATEGORY` | Tworzy podkategorię pod istniejącą kategorią nadrzędną |
| `MAP_TO_UNCATEGORIZED` | Mapuje na specjalną kategorię "Uncategorized" |

> **Uwaga:** Akcja `MAP_TO_EXISTING` została usunięta, ponieważ można zaimportować tylko jeden plik per CashFlow, więc kategorie są albo tworzone nowe albo mapowane na Uncategorized.

---

### 2. staged_transactions

Tymczasowe przechowywanie przetworzonych transakcji przed importem do CashFlow.

**Collection name:** `staged_transactions`

**Document structure:**

```json
{
  "_id": "ObjectId",
  "stagedTransactionId": "UUID (String)",
  "cashFlowId": "UUID (String)",
  "stagingSessionId": "UUID (String)",

  "originalData": {
    "bankTransactionId": "String",
    "name": "String",
    "description": "String | null",
    "bankCategory": "String",
    "money": {
      "amount": "Decimal",
      "currency": "String"
    },
    "type": "INFLOW | OUTFLOW",
    "paidDate": "ISODate"
  },

  "mappedData": {
    "name": "String",
    "description": "String | null",
    "categoryName": "String",
    "parentCategoryName": "String | null",
    "money": {
      "amount": "Decimal",
      "currency": "String"
    },
    "type": "INFLOW | OUTFLOW",
    "paidDate": "ISODate"
  },

  "validation": {
    "status": "VALID | INVALID | DUPLICATE | PENDING_MAPPING",
    "errors": ["String"],
    "isDuplicate": "Boolean",
    "duplicateOf": "String | null"
  },

  "isImport": "Boolean",
  "createdAt": "ISODate",
  "expiresAt": "ISODate"
}
```

**Nowe pole `isImport`:**

Pole `isImport` określa czy transakcja powinna być traktowana jako import historyczny:
- `true` - transakcja zostanie zaimportowana do CashFlow jako historyczna
- `false` - transakcja nie będzie importowana (np. zduplikowana)

**Indexes:**

```javascript
// TTL index - auto-delete after expiration
db.staged_transactions.createIndex(
  { "expiresAt": 1 },
  { expireAfterSeconds: 0 }
)

// Query by staging session
db.staged_transactions.createIndex({ "stagingSessionId": 1 })

// Query by cashFlowId
db.staged_transactions.createIndex({ "cashFlowId": 1 })

// Duplicate detection
db.staged_transactions.createIndex(
  { "cashFlowId": 1, "originalData.bankTransactionId": 1 }
)
```

---

### 3. import_jobs

Przechowuje historię importów z pełnym śledzeniem postępu i możliwością rollback.

**Collection name:** `import_jobs`

**Document structure:**

```json
{
  "_id": "ObjectId",
  "jobId": "UUID (String)",
  "cashFlowId": "UUID (String)",
  "stagingSessionId": "UUID (String)",

  "status": "PENDING | PROCESSING | COMPLETED | FAILED | ROLLED_BACK | FINALIZED",

  "timestamps": {
    "createdAt": "ISODate",
    "startedAt": "ISODate | null",
    "completedAt": "ISODate | null",
    "rolledBackAt": "ISODate | null",
    "finalizedAt": "ISODate | null"
  },

  "input": {
    "totalTransactions": "Number",
    "validTransactions": "Number",
    "duplicateTransactions": "Number",
    "categoriesToCreate": "Number"
  },

  "progress": {
    "percentage": "Number (0-100)",
    "currentPhase": "CREATING_CATEGORIES | IMPORTING_TRANSACTIONS | null",
    "phases": [...]
  },

  "result": {
    "categoriesCreated": ["String"],
    "cashChangesCreated": ["UUID (String)"],
    "transactionsImported": "Number",
    "transactionsFailed": "Number",
    "errors": [...]
  },

  "rollbackData": {
    "canRollback": "Boolean",
    "rollbackDeadline": "ISODate | null",
    "createdCashChangeIds": ["UUID (String)"],
    "createdCategoryNames": ["String"]
  },

  "summary": {...}
}
```

---

## REST API

### Base URL

```
/api/v1/bank-data-ingestion/{cashFlowId}
```

---

### 1. Konfiguracja mapowań kategorii

#### 1.1 POST /mappings - Konfiguruj mapowania

**Endpoint:** `POST /api/v1/bank-data-ingestion/{cashFlowId}/mappings`

**Opis:** Zapisuje konfigurację mapowań kategorii bankowych. Zastępuje istniejące mapowania dla podanych kombinacji (bankCategoryName, categoryType).

**Request:**

```http
POST /api/v1/bank-data-ingestion/cf-abc-123/mappings
Content-Type: application/json

{
  "mappings": [
    {
      "bankCategoryName": "Zakupy kartą",
      "action": "CREATE_NEW",
      "targetCategoryName": "Groceries",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Netflix",
      "action": "CREATE_SUBCATEGORY",
      "targetCategoryName": "Netflix",
      "parentCategoryName": "Subscriptions",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Opłata bankowa",
      "action": "MAP_TO_UNCATEGORIZED",
      "categoryType": "OUTFLOW"
    }
  ]
}
```

**Response (200 OK):**

```json
{
  "cashFlowId": "cf-abc-123",
  "mappingsConfigured": 3,
  "mappings": [
    {
      "mappingId": "mapping-uuid-001",
      "bankCategoryName": "Zakupy kartą",
      "targetCategoryName": "Groceries",
      "categoryType": "OUTFLOW",
      "action": "CREATE_NEW",
      "status": "CREATED"
    },
    ...
  ]
}
```

#### 1.2 GET /mappings - Pobierz mapowania

**Endpoint:** `GET /api/v1/bank-data-ingestion/{cashFlowId}/mappings`

**Response (200 OK):**

```json
{
  "cashFlowId": "cf-abc-123",
  "mappingsCount": 3,
  "mappings": [
    {
      "mappingId": "mapping-uuid-001",
      "bankCategoryName": "Zakupy kartą",
      "targetCategoryName": "Groceries",
      "parentCategoryName": null,
      "categoryType": "OUTFLOW",
      "action": "CREATE_NEW",
      "createdAt": "2026-01-15T10:00:00Z",
      "updatedAt": "2026-01-15T10:00:00Z"
    },
    ...
  ]
}
```

#### 1.3 DELETE /mappings/{mappingId} - Usuń pojedyncze mapowanie

**Endpoint:** `DELETE /api/v1/bank-data-ingestion/{cashFlowId}/mappings/{mappingId}`

**Response (200 OK):**

```json
{
  "deleted": true,
  "mappingId": "mapping-uuid-001",
  "bankCategoryName": "Zakupy kartą"
}
```

#### 1.4 DELETE /mappings - Usuń wszystkie mapowania

**Endpoint:** `DELETE /api/v1/bank-data-ingestion/{cashFlowId}/mappings`

**Response (200 OK):**

```json
{
  "deleted": true,
  "deletedCount": 5
}
```

---

### 2. Upload CSV

#### 2.1 POST /upload - Upload pliku CSV

**Endpoint:** `POST /api/v1/bank-data-ingestion/{cashFlowId}/upload`

**Content-Type:** `multipart/form-data`

**Opis:** Uploaduje plik CSV z transakcjami bankowymi w formacie `BankCsvRow`. Parser automatycznie przetwarza plik i tworzy sesję staging.

**Request:**

```http
POST /api/v1/bank-data-ingestion/cf-abc-123/upload
Content-Type: multipart/form-data

file: [plik CSV]
```

**Response (200 OK):**

```json
{
  "parseSummary": {
    "totalRows": 150,
    "successfulRows": 147,
    "failedRows": 3,
    "errors": [
      {
        "rowNumber": 45,
        "message": "Brakująca wartość dla pola 'amount'"
      },
      {
        "rowNumber": 89,
        "message": "Nieprawidłowy format daty: '32-13-2025'"
      }
    ]
  },
  "stagingResult": {
    "stagingSessionId": "session-uuid-100",
    "cashFlowId": "cf-abc-123",
    "status": "PENDING_MAPPING",
    "expiresAt": "2026-01-29T12:00:00Z",
    "summary": {
      "totalTransactions": 147,
      "validTransactions": 120,
      "invalidTransactions": 0,
      "duplicateTransactions": 5
    },
    "categoryBreakdown": [...],
    "unmappedCategories": [
      { "bankCategory": "Zakupy kartą", "count": 45, "type": "OUTFLOW" },
      { "bankCategory": "Przelew", "count": 20, "type": "INFLOW" }
    ]
  }
}
```

**Format pliku CSV - BankCsvRow:**

| Kolumna | Wymagana | Opis |
|---------|----------|------|
| `bankTransactionId` | Nie | Unikalny ID transakcji z banku. Jeśli brak, generowany jako hash(operationDate + amount + name) |
| `name` | Tak | Nazwa/tytuł transakcji |
| `description` | Nie | Pełny opis transakcji |
| `bankCategory` | Nie | Kategoria przypisana przez bank (domyślnie "Uncategorized") |
| `amount` | Tak | Kwota transakcji (zawsze dodatnia) |
| `currency` | Tak | Kod waluty (ISO 4217, np. "PLN", "EUR") |
| `type` | Tak | Typ transakcji: `INFLOW` lub `OUTFLOW` |
| `operationDate` | Tak | Data wykonania transakcji (YYYY-MM-DD) |
| `bookingDate` | Nie | Data zaksięgowania (domyślnie = operationDate) |
| `sourceAccountNumber` | Nie | Numer konta źródłowego (IBAN) |
| `targetAccountNumber` | Nie | Numer konta docelowego (IBAN) |

**Przykładowy plik CSV:**

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
ING-2026-001,NETFLIX.COM AMSTERDAM NLD,,Rozrywka,52.00,PLN,OUTFLOW,2026-01-15,2026-01-16,PL61109010140000071219812874,
ING-2026-002,BIEDRONKA SKLEP 1234,,Zakupy kartą,127.50,PLN,OUTFLOW,2026-01-16,2026-01-16,PL61109010140000071219812874,
ING-2026-003,PRZELEW OD PRACODAWCY,Wynagrodzenie styczeń,Wpływy regularne,5000.00,PLN,INFLOW,2026-01-10,2026-01-10,,PL61109010140000071219812874
,APTEKA POD LWEM,Leki,Zdrowie,89.99,PLN,OUTFLOW,2026-01-14,,PL61109010140000071219812874,
```

---

### 3. Staging

#### 3.1 GET /staging - Lista sesji staging

**Endpoint:** `GET /api/v1/bank-data-ingestion/{cashFlowId}/staging`

**Opis:** Zwraca listę wszystkich aktywnych (niewygasłych) sesji staging dla CashFlow. Pozwala użytkownikowi wrócić do niedokończonych importów.

**Response (200 OK):**

```json
{
  "cashFlowId": "cf-abc-123",
  "stagingSessions": [
    {
      "stagingSessionId": "session-uuid-100",
      "status": "READY_FOR_IMPORT",
      "createdAt": "2026-01-28T10:00:00Z",
      "expiresAt": "2026-01-29T10:00:00Z",
      "counts": {
        "totalTransactions": 147,
        "validTransactions": 144,
        "invalidTransactions": 0,
        "duplicateTransactions": 3
      }
    },
    {
      "stagingSessionId": "session-uuid-099",
      "status": "PENDING_MAPPING",
      "createdAt": "2026-01-27T14:00:00Z",
      "expiresAt": "2026-01-28T14:00:00Z",
      "counts": {
        "totalTransactions": 50,
        "validTransactions": 30,
        "invalidTransactions": 0,
        "duplicateTransactions": 2
      }
    }
  ],
  "hasPendingImport": true
}
```

#### 3.2 POST /staging - Stage transakcji (JSON)

**Endpoint:** `POST /api/v1/bank-data-ingestion/{cashFlowId}/staging`

**Opis:** Przetwarza transakcje z JSON, aplikuje mapowania i zapisuje do staging collection.

**Request:**

```http
POST /api/v1/bank-data-ingestion/cf-abc-123/staging
Content-Type: application/json

{
  "transactions": [
    {
      "bankTransactionId": "ING-2026-001",
      "name": "NETFLIX.COM AMSTERDAM NLD",
      "description": null,
      "bankCategory": "Rozrywka",
      "amount": 52.00,
      "currency": "PLN",
      "type": "OUTFLOW",
      "paidDate": "2026-01-15T10:00:00Z"
    },
    ...
  ]
}
```

**Response (200 OK):**

```json
{
  "stagingSessionId": "session-uuid-100",
  "cashFlowId": "cf-abc-123",
  "status": "READY_FOR_IMPORT",
  "expiresAt": "2026-01-29T12:00:00Z",
  "summary": {
    "totalTransactions": 150,
    "validTransactions": 147,
    "invalidTransactions": 0,
    "duplicateTransactions": 3
  },
  "categoryBreakdown": [...],
  "categoriesToCreate": [...],
  "monthlyBreakdown": [...],
  "duplicates": [...],
  "unmappedCategories": []
}
```

**Statusy sesji staging:**

| Status | Opis |
|--------|------|
| `PENDING_MAPPING` | Są kategorie bez mapowań - wymagana konfiguracja |
| `READY_FOR_IMPORT` | Wszystkie kategorie zmapowane - gotowe do importu |

#### 3.3 GET /staging/{stagingSessionId} - Preview staging

**Endpoint:** `GET /api/v1/bank-data-ingestion/{cashFlowId}/staging/{stagingSessionId}`

**Opis:** Pobiera pełny preview dla istniejącej sesji staging, włącznie z listą wszystkich transakcji.

**Response (200 OK):**

```json
{
  "stagingSessionId": "session-uuid-100",
  "cashFlowId": "cf-abc-123",
  "status": "READY_FOR_IMPORT",
  "expiresAt": "2026-01-29T12:00:00Z",
  "summary": {...},
  "transactions": [
    {
      "stagedTransactionId": "staged-uuid-001",
      "bankTransactionId": "ING-2026-001",
      "name": "NETFLIX.COM AMSTERDAM NLD",
      "description": null,
      "bankCategory": "Rozrywka",
      "targetCategory": "Netflix",
      "parentCategory": "Subscriptions",
      "amount": 52.00,
      "currency": "PLN",
      "type": "OUTFLOW",
      "paidDate": "2026-01-15T10:00:00Z",
      "validation": {
        "status": "VALID",
        "errors": [],
        "duplicateOf": null
      }
    },
    ...
  ],
  "categoryBreakdown": [...],
  "categoriesToCreate": [...],
  "monthlyBreakdown": [...]
}
```

#### 3.4 POST /staging/{stagingSessionId}/revalidate - Rewalidacja

**Endpoint:** `POST /api/v1/bank-data-ingestion/{cashFlowId}/staging/{stagingSessionId}/revalidate`

**Opis:** Rewaliduje sesję staging po skonfigurowaniu mapowań kategorii. Aktualizuje transakcje ze statusem `PENDING_MAPPING` aby miały prawidłowe zmapowane dane.

**Kiedy używać:**
1. Użytkownik uploaduje CSV
2. System zwraca `unmappedCategories` z listą kategorii bez mapowań
3. Użytkownik konfiguruje mapowania (`POST /mappings`)
4. Użytkownik wywołuje rewalidację aby zaktualizować staging
5. Jeśli nadal są niezmapowane kategorie - powtarza kroki 3-4

**Response (200 OK):**

```json
{
  "stagingSessionId": "session-uuid-100",
  "cashFlowId": "cf-abc-123",
  "status": "READY_FOR_IMPORT",
  "summary": {
    "totalTransactions": 147,
    "revalidatedCount": 45,
    "stillPendingCount": 0,
    "validCount": 144,
    "invalidCount": 0,
    "duplicateCount": 3
  },
  "stillUnmappedCategories": []
}
```

**Response gdy nadal są niezmapowane kategorie:**

```json
{
  "stagingSessionId": "session-uuid-100",
  "cashFlowId": "cf-abc-123",
  "status": "PENDING_MAPPING",
  "summary": {
    "totalTransactions": 147,
    "revalidatedCount": 30,
    "stillPendingCount": 15,
    "validCount": 129,
    "invalidCount": 0,
    "duplicateCount": 3
  },
  "stillUnmappedCategories": [
    "Zakupy online",
    "Przelew zagraniczny"
  ]
}
```

#### 3.5 DELETE /staging/{stagingSessionId} - Usuń staging

**Endpoint:** `DELETE /api/v1/bank-data-ingestion/{cashFlowId}/staging/{stagingSessionId}`

**Response (200 OK):**

```json
{
  "cashFlowId": "cf-abc-123",
  "stagingSessionId": "session-uuid-100",
  "deleted": true,
  "deletedCount": 147
}
```

---

### 4. Import Jobs

#### 4.1 POST /import - Rozpocznij import

**Endpoint:** `POST /api/v1/bank-data-ingestion/{cashFlowId}/import`

**Opis:** Rozpoczyna import ze staged transactions do CashFlow. Tworzy kategorie i importuje transakcje.

**Request:**

```http
POST /api/v1/bank-data-ingestion/cf-abc-123/import
Content-Type: application/json

{
  "stagingSessionId": "session-uuid-100"
}
```

**Response (202 Accepted):**

```json
{
  "jobId": "job-uuid-456",
  "cashFlowId": "cf-abc-123",
  "stagingSessionId": "session-uuid-100",
  "status": "PENDING",
  "input": {
    "totalTransactions": 147,
    "validTransactions": 144,
    "duplicateTransactions": 3,
    "categoriesToCreate": 2
  },
  "progress": {
    "percentage": 0,
    "currentPhase": null,
    "phases": [...]
  },
  "canRollback": true,
  "pollUrl": "/api/v1/bank-data-ingestion/cf-abc-123/import/job-uuid-456"
}
```

#### 4.2 GET /import/{jobId} - Postęp importu

**Endpoint:** `GET /api/v1/bank-data-ingestion/{cashFlowId}/import/{jobId}`

**Response (200 OK) - W trakcie:**

```json
{
  "jobId": "job-uuid-456",
  "cashFlowId": "cf-abc-123",
  "status": "PROCESSING",
  "progress": {
    "percentage": 36,
    "currentPhase": "IMPORTING_TRANSACTIONS",
    "phases": [
      {
        "name": "CREATING_CATEGORIES",
        "status": "COMPLETED",
        "processed": 2,
        "total": 2,
        "durationMs": 1000
      },
      {
        "name": "IMPORTING_TRANSACTIONS",
        "status": "IN_PROGRESS",
        "processed": 52,
        "total": 144
      }
    ]
  },
  "canRollback": true,
  "elapsedTimeMs": 45000
}
```

**Response (200 OK) - Zakończony:**

```json
{
  "jobId": "job-uuid-456",
  "cashFlowId": "cf-abc-123",
  "status": "COMPLETED",
  "progress": {
    "percentage": 100,
    "currentPhase": null,
    "phases": [...]
  },
  "result": {
    "categoriesCreated": ["Netflix", "Groceries"],
    "transactionsImported": 144,
    "transactionsFailed": 0,
    "errors": []
  },
  "summary": {
    "categoryBreakdown": [...],
    "monthlyBreakdown": [...],
    "totalDurationMs": 329000
  },
  "canRollback": true,
  "rollbackDeadline": "2026-01-28T13:15:30Z"
}
```

#### 4.3 GET /import - Lista import jobs

**Endpoint:** `GET /api/v1/bank-data-ingestion/{cashFlowId}/import`

**Query params:**
- `status` (opcjonalny) - filtruj po statusach: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `ROLLED_BACK`, `FINALIZED`

**Response (200 OK):**

```json
{
  "cashFlowId": "cf-abc-123",
  "jobs": [
    {
      "jobId": "job-uuid-456",
      "status": "COMPLETED",
      "createdAt": "2026-01-28T12:10:00Z",
      "completedAt": "2026-01-28T12:15:30Z",
      "transactionsImported": 144,
      "categoriesCreated": 2,
      "canRollback": true
    }
  ]
}
```

#### 4.4 POST /import/{jobId}/rollback - Rollback importu

**Endpoint:** `POST /api/v1/bank-data-ingestion/{cashFlowId}/import/{jobId}/rollback`

**Response (200 OK):**

```json
{
  "jobId": "job-uuid-456",
  "status": "ROLLED_BACK",
  "rollbackSummary": {
    "transactionsDeleted": 144,
    "categoriesDeleted": 2,
    "rollbackDurationMs": 5000
  }
}
```

#### 4.5 POST /import/{jobId}/finalize - Finalizuj import

**Endpoint:** `POST /api/v1/bank-data-ingestion/{cashFlowId}/import/{jobId}/finalize`

**Request:**

```http
POST /api/v1/bank-data-ingestion/cf-abc-123/import/job-uuid-456/finalize
Content-Type: application/json

{
  "deleteMappings": false
}
```

**Response (200 OK):**

```json
{
  "jobId": "job-uuid-456",
  "status": "FINALIZED",
  "cleanup": {
    "stagedTransactionsDeleted": 147,
    "mappingsDeleted": 0
  },
  "finalSummary": {
    "importedAt": "2026-01-28T12:15:30Z",
    "totalDurationMs": 329000,
    "categoriesCreated": 2,
    "transactionsImported": 144,
    "categoryBreakdown": [...]
  }
}
```

---

## Upload CSV

### Workflow importu z CSV

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CSV UPLOAD WORKFLOW                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. POST /upload                                                            │
│     ├── Parser parsuje CSV → BankCsvRow                                    │
│     ├── Walidacja każdego wiersza                                          │
│     └── Automatyczne tworzenie sesji staging                               │
│                              │                                              │
│                              ▼                                              │
│  2. Sprawdź unmappedCategories                                             │
│     ├── Jeśli puste → gotowe do importu                                   │
│     └── Jeśli niepuste → konfiguruj mapowania                             │
│                              │                                              │
│                              ▼                                              │
│  3. POST /mappings (jeśli potrzebne)                                       │
│     └── Skonfiguruj mapowania dla każdej kategorii bankowej               │
│                              │                                              │
│                              ▼                                              │
│  4. POST /staging/{id}/revalidate                                          │
│     ├── Aktualizuje transakcje PENDING_MAPPING                             │
│     └── Sprawdza czy wszystko zmapowane                                    │
│                              │                                              │
│           ┌──────────────────┴──────────────────┐                          │
│           ▼                                      ▼                          │
│  stillUnmappedCategories?              status = READY_FOR_IMPORT           │
│  → wróć do kroku 3                              │                          │
│                                                  ▼                          │
│                                        5. POST /import                      │
│                                           └── Rozpocznij import            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Struktura BankCsvRow

```java
public record BankCsvRow(
    String bankTransactionId,    // OPCJONALNE - jeśli null, generowany hash
    String name,                 // WYMAGANE
    String description,          // OPCJONALNE - jeśli null, pusty string
    String bankCategory,         // OPCJONALNE - jeśli null, "Uncategorized"
    BigDecimal amount,           // WYMAGANE - zawsze dodatnia
    String currency,             // WYMAGANE - ISO 4217
    Type type,                   // WYMAGANE - INFLOW lub OUTFLOW
    LocalDate operationDate,     // WYMAGANE
    LocalDate bookingDate,       // OPCJONALNE - domyślnie operationDate
    String sourceAccountNumber,  // OPCJONALNE
    String targetAccountNumber   // OPCJONALNE
)
```

### Przykład kompletnego flow

```bash
# 1. Upload CSV
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf-abc-123/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@bank_export.csv"

# Odpowiedź zawiera unmappedCategories: ["Zakupy kartą", "Rozrywka"]

# 2. Konfiguruj mapowania
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf-abc-123/mappings" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "mappings": [
      {"bankCategoryName": "Zakupy kartą", "action": "CREATE_NEW", "targetCategoryName": "Groceries", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Rozrywka", "action": "CREATE_NEW", "targetCategoryName": "Entertainment", "categoryType": "OUTFLOW"}
    ]
  }'

# 3. Rewaliduj staging
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf-abc-123/staging/session-uuid-100/revalidate" \
  -H "Authorization: Bearer $TOKEN"

# 4. Rozpocznij import
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf-abc-123/import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"stagingSessionId": "session-uuid-100"}'

# 5. Sprawdź postęp
curl "http://localhost:9090/api/v1/bank-data-ingestion/cf-abc-123/import/job-uuid-456" \
  -H "Authorization: Bearer $TOKEN"

# 6. Finalizuj
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf-abc-123/import/job-uuid-456/finalize" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"deleteMappings": false}'
```

---

## User Journey

### Pełny flow importu danych z banku

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    COMPLETE IMPORT USER JOURNEY                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 1: User creates CashFlow with history                          │   │
│  │                                                                     │   │
│  │ POST /api/v1/cash-flow/with-history                                │   │
│  │ CashFlow.status = SETUP                                             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 2: User uploads CSV file                                       │   │
│  │                                                                     │   │
│  │ POST /api/v1/bank-data-ingestion/{id}/upload                       │   │
│  │                                                                     │   │
│  │ - Server parsuje CSV → BankCsvRow                                  │   │
│  │ - Automatycznie tworzy sesję staging                               │   │
│  │ - Zwraca unmappedCategories do skonfigurowania                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 3: User configures category mappings                          │   │
│  │                                                                     │   │
│  │ POST /api/v1/bank-data-ingestion/{id}/mappings                     │   │
│  │                                                                     │   │
│  │ For each bank category, user selects:                              │   │
│  │ - CREATE_NEW: create new category                                  │   │
│  │ - CREATE_SUBCATEGORY: create under existing                        │   │
│  │ - MAP_TO_UNCATEGORIZED: use Uncategorized                          │   │
│  │                                                                     │   │
│  │ Mappings saved to: category_mappings collection                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 4: User revalidates staging                                   │   │
│  │                                                                     │   │
│  │ POST /api/v1/bank-data-ingestion/{id}/staging/{sessionId}/revalidate│   │
│  │                                                                     │   │
│  │ - Updates PENDING_MAPPING transactions                              │   │
│  │ - Returns stillUnmappedCategories (if any)                         │   │
│  │ - If empty → ready for import                                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 5: User reviews staging preview                               │   │
│  │                                                                     │   │
│  │ GET /api/v1/bank-data-ingestion/{id}/staging/{sessionId}           │   │
│  │                                                                     │   │
│  │ User sees:                                                         │   │
│  │ - "80 transactions → Groceries ($4,250)"                           │   │
│  │ - "3 transactions → Netflix (NEW) ($156)"                          │   │
│  │ - "3 duplicates skipped"                                           │   │
│  │                                                                     │   │
│  │ Options:                                                           │   │
│  │ [Modify mappings] [Start import] [Cancel]                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 6: User starts import                                         │   │
│  │                                                                     │   │
│  │ POST /api/v1/bank-data-ingestion/{id}/import                       │   │
│  │                                                                     │   │
│  │ Import job created in: import_jobs collection                      │   │
│  │                                                                     │   │
│  │ Phases:                                                            │   │
│  │ 1. CREATING_CATEGORIES (2 new) ████████████ 100% ✓                │   │
│  │ 2. IMPORTING_TRANSACTIONS       ████████░░░░  67%                  │   │
│  │                                                                     │   │
│  │ Overall progress: [████████████████░░░░░░░░░░░░░] 68%              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 7: Import completes - User decides                            │   │
│  │                                                                     │   │
│  │ [Rollback] - cofnij wszystko                                       │   │
│  │ [Finalize] - zatwierdź i wyczyść staging                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 8: User attests CashFlow                                      │   │
│  │                                                                     │   │
│  │ POST /api/v1/cash-flow/{id}/attest-historical-import              │   │
│  │                                                                     │   │
│  │ CashFlow.status = OPEN                                             │   │
│  │ Rollback no longer possible                                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Konfiguracja

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `INGESTION_STAGING_TTL_HOURS` | 24 | TTL dla staged_transactions (godziny) |
| `INGESTION_ROLLBACK_WINDOW_HOURS` | 1 | Okno czasowe na rollback po zakończeniu importu |
| `INGESTION_BATCH_SIZE` | 50 | Liczba transakcji przetwarzanych w jednym batch'u |
| `INGESTION_PROGRESS_UPDATE_INTERVAL` | 10 | Co ile transakcji aktualizować progress |

### Application Properties

```yaml
vidulum:
  ingestion:
    staging:
      ttl-hours: 24
    rollback:
      window-hours: 1
    processing:
      batch-size: 50
      progress-update-interval: 10
  bank-data-ingestion:
    limits:
      max-file-size-mb: 20
      max-transactions-per-staging: 20000
      max-files-per-upload: 10
      allowed-extensions: [csv, xlsx, xls]
```

---

## Rollback Mechanism

### Kiedy rollback jest możliwy?

| Status CashFlow | Status Job | Rollback możliwy? |
|-----------------|------------|-------------------|
| SETUP | COMPLETED | Tak |
| SETUP | FINALIZED | Tak (do rollbackDeadline) |
| OPEN (attested) | any | Nie |

### Co jest rollbackowane?

1. **Transakcje** - wszystkie cashChanges z `rollbackData.createdCashChangeIds`
2. **Kategorie** - wszystkie kategorie z `rollbackData.createdCategoryNames` (jeśli nie mają innych transakcji)
3. **Events** - emitowane eventy do aktualizacji ForecastStatement

### Co NIE jest rollbackowane?

1. **Mappings** - zostają do ponownego użycia
2. **Staging data** - zostaje do ponownego importu (jeśli przed finalize)
3. **Job history** - pozostaje jako audit trail

---

## Przyszłość - Mikroserwis

### Wydzielenie jako osobny serwis

Moduł `bank_data_ingestion` jest zaprojektowany z myślą o łatwym wydzieleniu:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FUTURE MICROSERVICE ARCHITECTURE                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────┐         ┌─────────────────────┐                   │
│  │  bank-data-         │         │  cashflow-service   │                   │
│  │  ingestion-service  │         │  (existing)         │                   │
│  │                     │         │                     │                   │
│  │  - Mappings API     │  HTTP   │  - CashFlow API     │                   │
│  │  - Staging API      │ ──────▶ │  - Import API       │                   │
│  │  - Import Jobs API  │         │  - Forecast API     │                   │
│  │  - CSV Upload API   │         │                     │                   │
│  │                     │         │                     │                   │
│  │  MongoDB:           │         │  MongoDB:           │                   │
│  │  - category_mappings│         │  - cashflow         │                   │
│  │  - staged_trans.    │         │  - forecast_stmt    │                   │
│  │  - import_jobs      │         │                     │                   │
│  └─────────────────────┘         └─────────────────────┘                   │
│           │                                │                               │
│           │            Kafka               │                               │
│           └────────────────────────────────┘                               │
│                    Events:                                                  │
│                    - ImportStarted                                          │
│                    - ImportCompleted                                        │
│                    - ImportRolledBack                                       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Changelog

| Data | Zmiany |
|------|--------|
| 2024-01-XX | Utworzenie dokumentu |
| 2024-01-XX | Szczegółowy opis kolekcji MongoDB |
| 2024-01-XX | Pełna specyfikacja REST API |
| 2024-01-XX | User Journey diagram |
| 2026-01-10 | Dodano sekcję Design Decisions: import przez system, kategorie przez import, merge plików, limity |
| 2026-01-28 | **Aktualizacja dokumentacji do aktualnego stanu kodu:** |
|            | - Dodano endpoint `GET /staging` - lista sesji staging |
|            | - Dodano endpoint `POST /staging/{id}/revalidate` - rewalidacja po dodaniu mapowań |
|            | - Dodano endpoint `POST /upload` - upload pliku CSV |
|            | - Dodano sekcję "Upload CSV" z formatem BankCsvRow i przykładami |
|            | - Zaktualizowano MappingAction: usunięto `MAP_TO_EXISTING` |
|            | - Dodano pole `isImport` w staged_transactions |
|            | - Zaktualizowano ścieżki endpointów do `/api/v1/bank-data-ingestion/{cashFlowId}/...` |
|            | - Dodano status `PENDING_MAPPING` dla sesji staging |
|            | - Poprawiono workflow z dodanym krokiem rewalidacji |
