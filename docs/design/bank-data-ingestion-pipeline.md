# Bank Data Ingestion Pipeline - Technical Design

## Spis treści

1. [Przegląd](#przegląd)
2. [Architektura](#architektura)
3. [MongoDB Collections](#mongodb-collections)
4. [REST API](#rest-api)
5. [User Journey](#user-journey)
6. [Konfiguracja](#konfiguracja)
7. [Rollback Mechanism](#rollback-mechanism)
8. [Przyszłość - Mikroserwis](#przyszłość---mikroserwis)dostalem

---

## Przegląd

Bank Data Ingestion Pipeline to moduł odpowiedzialny za bezpieczny, etapowy import danych z banków (CSV/API) do systemu CashFlow.

### Kluczowe cechy

- **Staged import** - dane są walidowane i przetwarzane przed zapisem do CashFlow
- **Preview przed importem** - user widzi dokładne podsumowanie przed zatwierdzeniem
- **Progress tracking** - śledzenie postępu importu w czasie rzeczywistym
- **Rollback** - możliwość cofnięcia importu przed attestacją CashFlow
- **Historia importów** - pełna historia co, kiedy i ile zostało zaimportowane

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
│  [client/       [category_        [staged_          [cashflow +            │
│   future PR]     mappings]         transactions]     import_jobs]          │
│                                                                             │
│  Temporary      Reusable          Temporary         Permanent              │
│  (memory)       (kept for         (TTL: 24h)        (history)              │
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
│   └── enums/
│       ├── MappingAction.java         # CREATE_NEW, CREATE_SUBCATEGORY, etc.
│       ├── ImportJobStatus.java       # PENDING, PROCESSING, COMPLETED, etc.
│       ├── ImportPhase.java           # CREATING_CATEGORIES, IMPORTING_TRANSACTIONS
│       └── ValidationStatus.java      # VALID, INVALID, DUPLICATE
│
├── app/
│   ├── commands/
│   │   ├── configure_mapping/
│   │   │   ├── ConfigureCategoryMappingCommand.java
│   │   │   └── ConfigureCategoryMappingCommandHandler.java
│   │   ├── delete_mapping/
│   │   │   ├── DeleteCategoryMappingCommand.java
│   │   │   └── DeleteCategoryMappingCommandHandler.java
│   │   ├── stage_transactions/
│   │   │   ├── StageTransactionsCommand.java
│   │   │   └── StageTransactionsCommandHandler.java
│   │   ├── start_import/
│   │   │   ├── StartImportJobCommand.java
│   │   │   └── StartImportJobCommandHandler.java
│   │   ├── rollback_import/
│   │   │   ├── RollbackImportCommand.java
│   │   │   └── RollbackImportCommandHandler.java
│   │   └── finalize_import/
│   │       ├── FinalizeImportCommand.java
│   │       └── FinalizeImportCommandHandler.java
│   │
│   └── queries/
│       ├── get_mappings/
│       │   ├── GetCategoryMappingsQuery.java
│       │   └── GetCategoryMappingsQueryHandler.java
│       ├── get_staging_preview/
│       │   ├── GetStagingPreviewQuery.java
│       │   └── GetStagingPreviewQueryHandler.java
│       └── get_import_progress/
│           ├── GetImportProgressQuery.java
│           └── GetImportProgressQueryHandler.java
│
├── infrastructure/
│   ├── CategoryMappingMongoRepository.java
│   ├── StagedTransactionMongoRepository.java
│   ├── ImportJobMongoRepository.java
│   └── entities/
│       ├── CategoryMappingEntity.java
│       ├── StagedTransactionEntity.java
│       └── ImportJobEntity.java
│
└── api/
    ├── BankDataIngestionController.java
    └── dto/
        ├── ConfigureMappingRequest.java
        ├── ConfigureMappingResponse.java
        ├── StageTransactionsRequest.java
        ├── StagingPreviewResponse.java
        ├── StartImportRequest.java
        ├── ImportProgressResponse.java
        └── RollbackResponse.java
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
  "action": "CREATE_NEW | CREATE_SUBCATEGORY | MAP_TO_EXISTING | MAP_TO_UNCATEGORIZED",
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

**Field descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `mappingId` | UUID | Yes | Unique identifier |
| `cashFlowId` | UUID | Yes | Reference to CashFlow |
| `bankCategoryName` | String | Yes | Original category name from bank (e.g., "Przelew własny") |
| `targetCategoryName` | String | Yes | Target category in system (e.g., "Transfers") |
| `parentCategoryName` | String | No | Parent category for CREATE_SUBCATEGORY action |
| `categoryType` | Enum | Yes | INFLOW or OUTFLOW |
| `action` | Enum | Yes | What to do: CREATE_NEW, CREATE_SUBCATEGORY, MAP_TO_EXISTING, MAP_TO_UNCATEGORIZED |
| `createdAt` | DateTime | Yes | Creation timestamp |
| `updatedAt` | DateTime | Yes | Last update timestamp |

**Example documents:**

```json
// Mapping to existing category
{
  "_id": "ObjectId('...')",
  "mappingId": "mapping-uuid-001",
  "cashFlowId": "cf-abc-123",
  "bankCategoryName": "Zakupy kartą",
  "targetCategoryName": "Groceries",
  "parentCategoryName": null,
  "categoryType": "OUTFLOW",
  "action": "MAP_TO_EXISTING",
  "createdAt": "2024-01-15T10:00:00Z",
  "updatedAt": "2024-01-15T10:00:00Z"
}

// Creating new subcategory
{
  "_id": "ObjectId('...')",
  "mappingId": "mapping-uuid-002",
  "cashFlowId": "cf-abc-123",
  "bankCategoryName": "Netflix",
  "targetCategoryName": "Netflix",
  "parentCategoryName": "Subscriptions",
  "categoryType": "OUTFLOW",
  "action": "CREATE_SUBCATEGORY",
  "createdAt": "2024-01-15T10:00:00Z",
  "updatedAt": "2024-01-15T10:00:00Z"
}

// Same bank category, different type (INFLOW vs OUTFLOW)
{
  "_id": "ObjectId('...')",
  "mappingId": "mapping-uuid-003",
  "cashFlowId": "cf-abc-123",
  "bankCategoryName": "Przelew własny",
  "targetCategoryName": "Transfers Out",
  "parentCategoryName": null,
  "categoryType": "OUTFLOW",
  "action": "CREATE_NEW",
  "createdAt": "2024-01-15T10:00:00Z",
  "updatedAt": "2024-01-15T10:00:00Z"
}

{
  "_id": "ObjectId('...')",
  "mappingId": "mapping-uuid-004",
  "cashFlowId": "cf-abc-123",
  "bankCategoryName": "Przelew własny",
  "targetCategoryName": "Transfers In",
  "parentCategoryName": null,
  "categoryType": "INFLOW",
  "action": "CREATE_NEW",
  "createdAt": "2024-01-15T10:00:00Z",
  "updatedAt": "2024-01-15T10:00:00Z"
}
```

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
    "status": "VALID | INVALID | DUPLICATE",
    "errors": ["String"],
    "isDuplicate": "Boolean",
    "duplicateOf": "String | null"
  },

  "createdAt": "ISODate",
  "expiresAt": "ISODate"
}
```

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

**Field descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `stagedTransactionId` | UUID | Yes | Unique identifier |
| `cashFlowId` | UUID | Yes | Reference to CashFlow |
| `stagingSessionId` | UUID | Yes | Groups transactions from same staging operation |
| `originalData` | Object | Yes | Original data from bank |
| `originalData.bankTransactionId` | String | Yes | Bank's transaction ID (for deduplication) |
| `originalData.name` | String | Yes | Transaction name from bank |
| `originalData.bankCategory` | String | Yes | Category from bank statement |
| `mappedData` | Object | Yes | Data after applying mappings |
| `mappedData.categoryName` | String | Yes | Target category name |
| `mappedData.parentCategoryName` | String | No | Parent category if subcategory |
| `validation.status` | Enum | Yes | VALID, INVALID, or DUPLICATE |
| `validation.errors` | Array | Yes | List of validation errors |
| `validation.isDuplicate` | Boolean | Yes | True if duplicate detected |
| `expiresAt` | DateTime | Yes | TTL expiration (default: createdAt + 24h) |

**Example documents:**

```json
// Valid transaction
{
  "_id": "ObjectId('...')",
  "stagedTransactionId": "staged-uuid-001",
  "cashFlowId": "cf-abc-123",
  "stagingSessionId": "session-uuid-100",

  "originalData": {
    "bankTransactionId": "ING-2024-001",
    "name": "NETFLIX.COM AMSTERDAM NLD",
    "description": null,
    "bankCategory": "Netflix",
    "money": { "amount": 52.00, "currency": "PLN" },
    "type": "OUTFLOW",
    "paidDate": "2024-01-15T10:00:00Z"
  },

  "mappedData": {
    "name": "NETFLIX.COM AMSTERDAM NLD",
    "description": null,
    "categoryName": "Netflix",
    "parentCategoryName": "Subscriptions",
    "money": { "amount": 52.00, "currency": "PLN" },
    "type": "OUTFLOW",
    "paidDate": "2024-01-15T10:00:00Z"
  },

  "validation": {
    "status": "VALID",
    "errors": [],
    "isDuplicate": false,
    "duplicateOf": null
  },

  "createdAt": "2024-01-15T12:00:00Z",
  "expiresAt": "2024-01-16T12:00:00Z"
}

// Duplicate transaction
{
  "_id": "ObjectId('...')",
  "stagedTransactionId": "staged-uuid-002",
  "cashFlowId": "cf-abc-123",
  "stagingSessionId": "session-uuid-100",

  "originalData": {
    "bankTransactionId": "ING-2024-001",
    "name": "NETFLIX.COM AMSTERDAM NLD",
    "description": null,
    "bankCategory": "Netflix",
    "money": { "amount": 52.00, "currency": "PLN" },
    "type": "OUTFLOW",
    "paidDate": "2024-01-15T10:00:00Z"
  },

  "mappedData": {
    "name": "NETFLIX.COM AMSTERDAM NLD",
    "description": null,
    "categoryName": "Netflix",
    "parentCategoryName": "Subscriptions",
    "money": { "amount": 52.00, "currency": "PLN" },
    "type": "OUTFLOW",
    "paidDate": "2024-01-15T10:00:00Z"
  },

  "validation": {
    "status": "DUPLICATE",
    "errors": ["Transaction with bankTransactionId ING-2024-001 already exists"],
    "isDuplicate": true,
    "duplicateOf": "cc-existing-123"
  },

  "createdAt": "2024-01-15T12:00:00Z",
  "expiresAt": "2024-01-16T12:00:00Z"
}

// Invalid transaction (date outside range)
{
  "_id": "ObjectId('...')",
  "stagedTransactionId": "staged-uuid-003",
  "cashFlowId": "cf-abc-123",
  "stagingSessionId": "session-uuid-100",

  "originalData": {
    "bankTransactionId": "ING-2024-999",
    "name": "Some transaction",
    "description": null,
    "bankCategory": "Other",
    "money": { "amount": 100.00, "currency": "PLN" },
    "type": "OUTFLOW",
    "paidDate": "2024-06-15T10:00:00Z"
  },

  "mappedData": {
    "name": "Some transaction",
    "description": null,
    "categoryName": "Uncategorized",
    "parentCategoryName": null,
    "money": { "amount": 100.00, "currency": "PLN" },
    "type": "OUTFLOW",
    "paidDate": "2024-06-15T10:00:00Z"
  },

  "validation": {
    "status": "INVALID",
    "errors": [
      "paidDate 2024-06-15 is not before activePeriod 2024-02"
    ],
    "isDuplicate": false,
    "duplicateOf": null
  },

  "createdAt": "2024-01-15T12:00:00Z",
  "expiresAt": "2024-01-16T12:00:00Z"
}
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
    "phases": [
      {
        "name": "CREATING_CATEGORIES",
        "status": "PENDING | IN_PROGRESS | COMPLETED | FAILED",
        "processed": "Number",
        "total": "Number",
        "startedAt": "ISODate | null",
        "completedAt": "ISODate | null",
        "durationMs": "Number | null"
      },
      {
        "name": "IMPORTING_TRANSACTIONS",
        "status": "PENDING | IN_PROGRESS | COMPLETED | FAILED",
        "processed": "Number",
        "total": "Number",
        "startedAt": "ISODate | null",
        "completedAt": "ISODate | null",
        "durationMs": "Number | null"
      }
    ]
  },

  "result": {
    "categoriesCreated": ["String"],
    "cashChangesCreated": ["UUID (String)"],
    "transactionsImported": "Number",
    "transactionsFailed": "Number",
    "errors": [
      {
        "bankTransactionId": "String",
        "error": "String"
      }
    ]
  },

  "rollbackData": {
    "canRollback": "Boolean",
    "rollbackDeadline": "ISODate | null",
    "createdCashChangeIds": ["UUID (String)"],
    "createdCategoryNames": ["String"]
  },

  "summary": {
    "categoryBreakdown": [
      {
        "categoryName": "String",
        "parentCategory": "String | null",
        "transactionCount": "Number",
        "totalAmount": {
          "amount": "Decimal",
          "currency": "String"
        },
        "type": "INFLOW | OUTFLOW",
        "isNewCategory": "Boolean"
      }
    ],
    "monthlyBreakdown": [
      {
        "month": "String (YYYY-MM)",
        "inflowTotal": "Decimal",
        "outflowTotal": "Decimal",
        "transactionCount": "Number"
      }
    ],
    "totalDurationMs": "Number"
  }
}
```

**Indexes:**

```javascript
// Query by cashFlowId
db.import_jobs.createIndex({ "cashFlowId": 1 })

// Query active jobs
db.import_jobs.createIndex({ "cashFlowId": 1, "status": 1 })

// Query by staging session
db.import_jobs.createIndex({ "stagingSessionId": 1 })
```

**Field descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `jobId` | UUID | Yes | Unique identifier |
| `cashFlowId` | UUID | Yes | Reference to CashFlow |
| `stagingSessionId` | UUID | Yes | Reference to staging session |
| `status` | Enum | Yes | Current job status |
| `timestamps` | Object | Yes | All relevant timestamps |
| `input` | Object | Yes | Input statistics (from staging) |
| `progress` | Object | Yes | Real-time progress tracking |
| `progress.percentage` | Number | Yes | 0-100 for progress bar |
| `progress.phases` | Array | Yes | Detailed phase progress |
| `result` | Object | Yes | Import results (updated during processing) |
| `rollbackData` | Object | Yes | Data needed for rollback |
| `rollbackData.canRollback` | Boolean | Yes | Whether rollback is still possible |
| `summary` | Object | No | Final summary (populated on completion) |

**Example documents:**

```json
// Job in progress
{
  "_id": "ObjectId('...')",
  "jobId": "job-uuid-456",
  "cashFlowId": "cf-abc-123",
  "stagingSessionId": "session-uuid-100",

  "status": "PROCESSING",

  "timestamps": {
    "createdAt": "2024-01-15T12:10:00Z",
    "startedAt": "2024-01-15T12:10:01Z",
    "completedAt": null,
    "rolledBackAt": null,
    "finalizedAt": null
  },

  "input": {
    "totalTransactions": 150,
    "validTransactions": 147,
    "duplicateTransactions": 3,
    "categoriesToCreate": 2
  },

  "progress": {
    "percentage": 36,
    "currentPhase": "IMPORTING_TRANSACTIONS",
    "phases": [
      {
        "name": "CREATING_CATEGORIES",
        "status": "COMPLETED",
        "processed": 2,
        "total": 2,
        "startedAt": "2024-01-15T12:10:01Z",
        "completedAt": "2024-01-15T12:10:02Z",
        "durationMs": 1000
      },
      {
        "name": "IMPORTING_TRANSACTIONS",
        "status": "IN_PROGRESS",
        "processed": 52,
        "total": 147,
        "startedAt": "2024-01-15T12:10:02Z",
        "completedAt": null,
        "durationMs": null
      }
    ]
  },

  "result": {
    "categoriesCreated": ["Netflix", "Transfers"],
    "cashChangesCreated": ["cc-001", "cc-002", "cc-003", "...52 total..."],
    "transactionsImported": 52,
    "transactionsFailed": 0,
    "errors": []
  },

  "rollbackData": {
    "canRollback": true,
    "rollbackDeadline": null,
    "createdCashChangeIds": ["cc-001", "cc-002", "cc-003", "..."],
    "createdCategoryNames": ["Netflix", "Transfers"]
  },

  "summary": null
}

// Completed job
{
  "_id": "ObjectId('...')",
  "jobId": "job-uuid-456",
  "cashFlowId": "cf-abc-123",
  "stagingSessionId": "session-uuid-100",

  "status": "COMPLETED",

  "timestamps": {
    "createdAt": "2024-01-15T12:10:00Z",
    "startedAt": "2024-01-15T12:10:01Z",
    "completedAt": "2024-01-15T12:15:30Z",
    "rolledBackAt": null,
    "finalizedAt": null
  },

  "input": {
    "totalTransactions": 150,
    "validTransactions": 147,
    "duplicateTransactions": 3,
    "categoriesToCreate": 2
  },

  "progress": {
    "percentage": 100,
    "currentPhase": null,
    "phases": [
      {
        "name": "CREATING_CATEGORIES",
        "status": "COMPLETED",
        "processed": 2,
        "total": 2,
        "startedAt": "2024-01-15T12:10:01Z",
        "completedAt": "2024-01-15T12:10:02Z",
        "durationMs": 1000
      },
      {
        "name": "IMPORTING_TRANSACTIONS",
        "status": "COMPLETED",
        "processed": 147,
        "total": 147,
        "startedAt": "2024-01-15T12:10:02Z",
        "completedAt": "2024-01-15T12:15:30Z",
        "durationMs": 328000
      }
    ]
  },

  "result": {
    "categoriesCreated": ["Netflix", "Transfers"],
    "cashChangesCreated": ["cc-001", "cc-002", "...147 total..."],
    "transactionsImported": 147,
    "transactionsFailed": 0,
    "errors": []
  },

  "rollbackData": {
    "canRollback": true,
    "rollbackDeadline": "2024-01-15T13:15:30Z",
    "createdCashChangeIds": ["cc-001", "cc-002", "...147 total..."],
    "createdCategoryNames": ["Netflix", "Transfers"]
  },

  "summary": {
    "categoryBreakdown": [
      {
        "categoryName": "Groceries",
        "parentCategory": null,
        "transactionCount": 80,
        "totalAmount": { "amount": 4250.00, "currency": "PLN" },
        "type": "OUTFLOW",
        "isNewCategory": false
      },
      {
        "categoryName": "Netflix",
        "parentCategory": "Subscriptions",
        "transactionCount": 3,
        "totalAmount": { "amount": 156.00, "currency": "PLN" },
        "type": "OUTFLOW",
        "isNewCategory": true
      },
      {
        "categoryName": "Transfers",
        "parentCategory": null,
        "transactionCount": 25,
        "totalAmount": { "amount": 12500.00, "currency": "PLN" },
        "type": "OUTFLOW",
        "isNewCategory": true
      },
      {
        "categoryName": "Salary",
        "parentCategory": null,
        "transactionCount": 3,
        "totalAmount": { "amount": 15000.00, "currency": "PLN" },
        "type": "INFLOW",
        "isNewCategory": false
      }
    ],
    "monthlyBreakdown": [
      {
        "month": "2024-01",
        "inflowTotal": 5000.00,
        "outflowTotal": 3200.00,
        "transactionCount": 52
      },
      {
        "month": "2024-02",
        "inflowTotal": 5000.00,
        "outflowTotal": 2800.00,
        "transactionCount": 48
      },
      {
        "month": "2024-03",
        "inflowTotal": 5000.00,
        "outflowTotal": 3100.00,
        "transactionCount": 47
      }
    ],
    "totalDurationMs": 329000
  }
}

// Rolled back job
{
  "_id": "ObjectId('...')",
  "jobId": "job-uuid-789",
  "cashFlowId": "cf-abc-123",
  "stagingSessionId": "session-uuid-200",

  "status": "ROLLED_BACK",

  "timestamps": {
    "createdAt": "2024-01-16T10:00:00Z",
    "startedAt": "2024-01-16T10:00:01Z",
    "completedAt": "2024-01-16T10:05:00Z",
    "rolledBackAt": "2024-01-16T10:10:00Z",
    "finalizedAt": null
  },

  "input": {
    "totalTransactions": 50,
    "validTransactions": 50,
    "duplicateTransactions": 0,
    "categoriesToCreate": 1
  },

  "progress": {
    "percentage": 100,
    "currentPhase": null,
    "phases": [...]
  },

  "result": {
    "categoriesCreated": ["NewCategory"],
    "cashChangesCreated": ["cc-101", "cc-102", "..."],
    "transactionsImported": 50,
    "transactionsFailed": 0,
    "errors": []
  },

  "rollbackData": {
    "canRollback": false,
    "rollbackDeadline": null,
    "createdCashChangeIds": [],
    "createdCategoryNames": []
  },

  "summary": {
    "categoryBreakdown": [...],
    "monthlyBreakdown": [...],
    "totalDurationMs": 299000,
    "rollbackSummary": {
      "transactionsDeleted": 50,
      "categoriesDeleted": 1,
      "rollbackDurationMs": 5000
    }
  }
}
```

---

## REST API

### Base URL

```
/api/v1/bank-data-ingestion/{cashFlowId}
```

---

### 1. Configure Category Mappings

**Endpoint:** `POST /api/v1/bank-data-ingestion/{cashFlowId}/mappings`

**Description:** Zapisuje konfigurację mapowań kategorii bankowych. Zastępuje istniejące mapowania dla podanych kombinacji (bankCategoryName, categoryType).

**Request:**

```http
POST /api/v1/bank-data-ingestion/cf-abc-123/mappings
Content-Type: application/json

{
  "mappings": [
    {
      "bankCategoryName": "Zakupy kartą",
      "action": "MAP_TO_EXISTING",
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
      "bankCategoryName": "Przelew własny",
      "action": "CREATE_NEW",
      "targetCategoryName": "Transfers Out",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Przelew własny",
      "action": "MAP_TO_EXISTING",
      "targetCategoryName": "Salary",
      "categoryType": "INFLOW"
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
  "mappingsConfigured": 5,
  "mappings": [
    {
      "mappingId": "mapping-uuid-001",
      "bankCategoryName": "Zakupy kartą",
      "targetCategoryName": "Groceries",
      "categoryType": "OUTFLOW",
      "action": "MAP_TO_EXISTING",
      "status": "CREATED"
    },
    {
      "mappingId": "mapping-uuid-002",
      "bankCategoryName": "Netflix",
      "targetCategoryName": "Netflix",
      "parentCategoryName": "Subscriptions",
      "categoryType": "OUTFLOW",
      "action": "CREATE_SUBCATEGORY",
      "status": "CREATED"
    },
    {
      "mappingId": "mapping-uuid-003",
      "bankCategoryName": "Przelew własny",
      "targetCategoryName": "Transfers Out",
      "categoryType": "OUTFLOW",
      "action": "CREATE_NEW",
      "status": "CREATED"
    },
    {
      "mappingId": "mapping-uuid-004",
      "bankCategoryName": "Przelew własny",
      "targetCategoryName": "Salary",
      "categoryType": "INFLOW",
      "action": "MAP_TO_EXISTING",
      "status": "CREATED"
    },
    {
      "mappingId": "mapping-uuid-005",
      "bankCategoryName": "Opłata bankowa",
      "targetCategoryName": "Uncategorized",
      "categoryType": "OUTFLOW",
      "action": "MAP_TO_UNCATEGORIZED",
      "status": "CREATED"
    }
  ]
}
```

**Errors:**

| Status | Error | Description |
|--------|-------|-------------|
| 404 | CashFlowNotFound | CashFlow nie istnieje |
| 400 | InvalidMappingAction | Nieznana akcja mapowania |
| 400 | ParentCategoryNotFound | Parent category nie istnieje (dla CREATE_SUBCATEGORY) |
| 400 | TargetCategoryNotFound | Target category nie istnieje (dla MAP_TO_EXISTING) |

---

### 2. Get Category Mappings

**Endpoint:** `GET /api/v1/bank-data-ingestion/{cashFlowId}/mappings`

**Description:** Pobiera wszystkie skonfigurowane mapowania dla CashFlow.

**Request:**

```http
GET /api/v1/bank-data-ingestion/cf-abc-123/mappings
```

**Response (200 OK):**

```json
{
  "cashFlowId": "cf-abc-123",
  "mappingsCount": 5,
  "mappings": [
    {
      "mappingId": "mapping-uuid-001",
      "bankCategoryName": "Zakupy kartą",
      "targetCategoryName": "Groceries",
      "parentCategoryName": null,
      "categoryType": "OUTFLOW",
      "action": "MAP_TO_EXISTING",
      "createdAt": "2024-01-15T10:00:00Z"
    },
    {
      "mappingId": "mapping-uuid-002",
      "bankCategoryName": "Netflix",
      "targetCategoryName": "Netflix",
      "parentCategoryName": "Subscriptions",
      "categoryType": "OUTFLOW",
      "action": "CREATE_SUBCATEGORY",
      "createdAt": "2024-01-15T10:00:00Z"
    }
  ]
}
```

---

### 3. Delete Category Mapping

**Endpoint:** `DELETE /api/v1/bank-data-ingestion/{cashFlowId}/mappings/{mappingId}`

**Description:** Usuwa pojedyncze mapowanie.

**Request:**

```http
DELETE /api/v1/bank-data-ingestion/cf-abc-123/mappings/mapping-uuid-001
```

**Response (200 OK):**

```json
{
  "deleted": true,
  "mappingId": "mapping-uuid-001",
  "bankCategoryName": "Zakupy kartą"
}
```

---

### 4. Delete All Mappings

**Endpoint:** `DELETE /api/v1/bank-data-ingestion/{cashFlowId}/mappings`

**Description:** Usuwa wszystkie mapowania dla CashFlow.

**Request:**

```http
DELETE /api/v1/bank-data-ingestion/cf-abc-123/mappings
```

**Response (200 OK):**

```json
{
  "deleted": true,
  "deletedCount": 5
}
```

---

### 5. Stage Transactions

**Endpoint:** `POST /api/v1/cash-flow/{cashFlowId}/ingestion/stage`

**Description:** Przetwarza transakcje z banku, aplikuje mapowania i zapisuje do staging collection. Zwraca preview z podsumowaniem.

**Request:**

```http
POST /api/v1/bank-data-ingestion/cf-abc-123/stage
Content-Type: application/json

{
  "transactions": [
    {
      "bankTransactionId": "ING-2024-001",
      "name": "NETFLIX.COM AMSTERDAM NLD",
      "description": null,
      "bankCategory": "Netflix",
      "money": { "amount": 52.00, "currency": "PLN" },
      "type": "OUTFLOW",
      "paidDate": "2024-01-15T10:00:00Z"
    },
    {
      "bankTransactionId": "ING-2024-002",
      "name": "BIEDRONKA SKLEP 1234",
      "description": null,
      "bankCategory": "Zakupy kartą",
      "money": { "amount": 127.50, "currency": "PLN" },
      "type": "OUTFLOW",
      "paidDate": "2024-01-16T14:30:00Z"
    },
    {
      "bankTransactionId": "ING-2024-003",
      "name": "PRZELEW OD PRACODAWCY",
      "description": "Wynagrodzenie styczeń",
      "bankCategory": "Przelew własny",
      "money": { "amount": 5000.00, "currency": "PLN" },
      "type": "INFLOW",
      "paidDate": "2024-01-10T09:00:00Z"
    }
  ]
}
```

**Response (200 OK):**

```json
{
  "stagingSessionId": "session-uuid-100",
  "cashFlowId": "cf-abc-123",
  "status": "READY_FOR_IMPORT",
  "expiresAt": "2024-01-16T12:00:00Z",

  "summary": {
    "totalTransactions": 150,
    "validTransactions": 147,
    "invalidTransactions": 0,
    "duplicateTransactions": 3
  },

  "categoryBreakdown": [
    {
      "targetCategory": "Groceries",
      "parentCategory": null,
      "transactionCount": 80,
      "totalAmount": { "amount": 4250.00, "currency": "PLN" },
      "type": "OUTFLOW",
      "isNewCategory": false
    },
    {
      "targetCategory": "Netflix",
      "parentCategory": "Subscriptions",
      "transactionCount": 3,
      "totalAmount": { "amount": 156.00, "currency": "PLN" },
      "type": "OUTFLOW",
      "isNewCategory": true
    },
    {
      "targetCategory": "Transfers Out",
      "parentCategory": null,
      "transactionCount": 25,
      "totalAmount": { "amount": 12500.00, "currency": "PLN" },
      "type": "OUTFLOW",
      "isNewCategory": true
    },
    {
      "targetCategory": "Salary",
      "parentCategory": null,
      "transactionCount": 3,
      "totalAmount": { "amount": 15000.00, "currency": "PLN" },
      "type": "INFLOW",
      "isNewCategory": false
    }
  ],

  "categoriesToCreate": [
    { "name": "Netflix", "parent": "Subscriptions", "type": "OUTFLOW" },
    { "name": "Transfers Out", "parent": null, "type": "OUTFLOW" }
  ],

  "monthlyBreakdown": [
    { "month": "2024-01", "inflowTotal": 5000.00, "outflowTotal": 3200.00, "transactionCount": 52 },
    { "month": "2024-02", "inflowTotal": 5000.00, "outflowTotal": 2800.00, "transactionCount": 48 },
    { "month": "2024-03", "inflowTotal": 5000.00, "outflowTotal": 3100.00, "transactionCount": 47 }
  ],

  "duplicates": [
    {
      "bankTransactionId": "ING-2024-050",
      "name": "Duplicate transaction",
      "duplicateOf": "cc-existing-123"
    }
  ],

  "unmappedCategories": []
}
```

**Response with unmapped categories (400 Bad Request):**

```json
{
  "error": "UnmappedCategoriesFound",
  "message": "Some bank categories are not mapped",
  "unmappedCategories": [
    { "bankCategory": "Nowa kategoria", "count": 5, "type": "OUTFLOW" },
    { "bankCategory": "Inna kategoria", "count": 2, "type": "INFLOW" }
  ]
}
```

---

### 6. Get Staging Preview

**Endpoint:** `GET /api/v1/cash-flow/{cashFlowId}/ingestion/stage/{stagingSessionId}`

**Description:** Pobiera preview dla istniejącej sesji staging.

**Request:**

```http
GET /api/v1/bank-data-ingestion/cf-abc-123/stage/session-uuid-100
```

**Response (200 OK):**

```json
{
  "stagingSessionId": "session-uuid-100",
  "cashFlowId": "cf-abc-123",
  "status": "READY_FOR_IMPORT",
  "expiresAt": "2024-01-16T12:00:00Z",
  "summary": { ... },
  "categoryBreakdown": [ ... ],
  "monthlyBreakdown": [ ... ]
}
```

---

### 7. Delete Staging Session

**Endpoint:** `DELETE /api/v1/cash-flow/{cashFlowId}/ingestion/stage/{stagingSessionId}`

**Description:** Usuwa staging session i wszystkie staged transactions.

**Request:**

```http
DELETE /api/v1/bank-data-ingestion/cf-abc-123/stage/session-uuid-100
```

**Response (200 OK):**

```json
{
  "deleted": true,
  "stagingSessionId": "session-uuid-100",
  "transactionsDeleted": 147
}
```

---

### 8. Start Import Job

**Endpoint:** `POST /api/v1/cash-flow/{cashFlowId}/ingestion/import`

**Description:** Rozpoczyna import ze staged transactions do CashFlow.

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
    "categoriesToCreate": 2
  },
  "pollUrl": "/api/v1/bank-data-ingestion/cf-abc-123/import/job-uuid-456"
}
```

---

### 9. Get Import Progress

**Endpoint:** `GET /api/v1/cash-flow/{cashFlowId}/ingestion/import/{jobId}`

**Description:** Pobiera aktualny status i postęp importu.

**Request:**

```http
GET /api/v1/bank-data-ingestion/cf-abc-123/import/job-uuid-456
```

**Response (200 OK) - In Progress:**

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
        "total": 2
      },
      {
        "name": "IMPORTING_TRANSACTIONS",
        "status": "IN_PROGRESS",
        "processed": 52,
        "total": 147
      }
    ]
  },

  "canRollback": true,
  "elapsedTimeMs": 45000
}
```

**Response (200 OK) - Completed:**

```json
{
  "jobId": "job-uuid-456",
  "cashFlowId": "cf-abc-123",
  "status": "COMPLETED",

  "progress": {
    "percentage": 100,
    "currentPhase": null,
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
        "status": "COMPLETED",
        "processed": 147,
        "total": 147,
        "durationMs": 328000
      }
    ]
  },

  "result": {
    "categoriesCreated": ["Netflix", "Transfers Out"],
    "transactionsImported": 147,
    "transactionsFailed": 0
  },

  "summary": {
    "categoryBreakdown": [ ... ],
    "monthlyBreakdown": [ ... ],
    "totalDurationMs": 329000
  },

  "canRollback": true,
  "rollbackDeadline": "2024-01-15T13:15:30Z"
}
```

---

### 10. Rollback Import

**Endpoint:** `POST /api/v1/cash-flow/{cashFlowId}/ingestion/import/{jobId}/rollback`

**Description:** Wycofuje import - usuwa zaimportowane transakcje i kategorie.

**Request:**

```http
POST /api/v1/bank-data-ingestion/cf-abc-123/import/job-uuid-456/rollback
```

**Response (200 OK):**

```json
{
  "jobId": "job-uuid-456",
  "status": "ROLLED_BACK",
  "rollbackSummary": {
    "transactionsDeleted": 147,
    "categoriesDeleted": 2,
    "rollbackDurationMs": 5000
  }
}
```

**Response (400 Bad Request) - Rollback not allowed:**

```json
{
  "error": "RollbackNotAllowed",
  "message": "Rollback is no longer possible. CashFlow has been attested.",
  "jobId": "job-uuid-456",
  "canRollback": false
}
```

---

### 11. Finalize Import

**Endpoint:** `POST /api/v1/cash-flow/{cashFlowId}/ingestion/import/{jobId}/finalize`

**Description:** Finalizuje import - usuwa staging data, zachowuje historię.

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
    "importedAt": "2024-01-15T12:15:30Z",
    "totalDuration": "5m 29s",
    "categoriesCreated": 2,
    "transactionsImported": 147,
    "categoryBreakdown": [ ... ]
  }
}
```

---

### 12. List Import Jobs

**Endpoint:** `GET /api/v1/cash-flow/{cashFlowId}/ingestion/import`

**Description:** Lista wszystkich import jobs dla CashFlow.

**Request:**

```http
GET /api/v1/bank-data-ingestion/cf-abc-123/import?status=COMPLETED,FINALIZED
```

**Response (200 OK):**

```json
{
  "cashFlowId": "cf-abc-123",
  "jobs": [
    {
      "jobId": "job-uuid-456",
      "status": "FINALIZED",
      "createdAt": "2024-01-15T12:10:00Z",
      "completedAt": "2024-01-15T12:15:30Z",
      "transactionsImported": 147,
      "categoriesCreated": 2
    },
    {
      "jobId": "job-uuid-123",
      "status": "COMPLETED",
      "createdAt": "2024-01-10T10:00:00Z",
      "completedAt": "2024-01-10T10:05:00Z",
      "transactionsImported": 50,
      "categoriesCreated": 0
    }
  ]
}
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
│  │ STEP 2: User uploads CSV in UI (client-side parsing)               │   │
│  │                                                                     │   │
│  │ - UI parses CSV locally                                            │   │
│  │ - Extracts unique bank categories                                   │   │
│  │ - Shows list of categories to map                                  │   │
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
│  │ - MAP_TO_EXISTING: use existing category                           │   │
│  │ - MAP_TO_UNCATEGORIZED: use Uncategorized                          │   │
│  │                                                                     │   │
│  │ Mappings saved to: category_mappings collection                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 4: User stages transactions (preview)                         │   │
│  │                                                                     │   │
│  │ POST /api/v1/bank-data-ingestion/{id}/stage                        │   │
│  │                                                                     │   │
│  │ - Transactions validated against CashFlow rules                    │   │
│  │ - Mappings applied to bank categories                              │   │
│  │ - Duplicates detected                                              │   │
│  │ - Preview returned with full breakdown                             │   │
│  │                                                                     │   │
│  │ Saved to: staged_transactions collection (TTL: 24h)               │   │
│  │                                                                     │   │
│  │ User sees:                                                         │   │
│  │ - "80 transactions → Groceries ($4,250)"                           │   │
│  │ - "3 transactions → Netflix (NEW) ($156)"                          │   │
│  │ - "25 transactions → Transfers Out (NEW) ($12,500)"               │   │
│  │ - "3 duplicates skipped"                                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 5: User reviews and confirms                                  │   │
│  │                                                                     │   │
│  │ User can:                                                          │   │
│  │ - Modify mappings and re-stage                                     │   │
│  │ - Accept and start import                                          │   │
│  │ - Cancel and delete staging                                        │   │
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
│  │ STEP 7: Import completes                                           │   │
│  │                                                                     │   │
│  │ GET /api/v1/bank-data-ingestion/{id}/import/{jobId}               │   │
│  │                                                                     │   │
│  │ status: COMPLETED                                                  │   │
│  │ transactionsImported: 147                                          │   │
│  │ categoriesCreated: 2                                               │   │
│  │ totalDuration: 5m 29s                                              │   │
│  │                                                                     │   │
│  │ Options:                                                           │   │
│  │ [Rollback] [Finalize]                                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│            ┌─────────────────┴─────────────────┐                           │
│            ▼                                   ▼                           │
│  ┌─────────────────────┐           ┌─────────────────────┐                │
│  │ STEP 8a: Rollback   │           │ STEP 8b: Finalize   │                │
│  │                     │           │                     │                │
│  │ If something wrong: │           │ If all OK:          │                │
│  │ - Delete imported   │           │ - Delete staging    │                │
│  │   transactions      │           │ - Keep mappings     │                │
│  │ - Delete created    │           │ - Keep job history  │                │
│  │   categories        │           │                     │                │
│  │ - Staging data      │           │ status: FINALIZED   │                │
│  │   still available   │           │                     │                │
│  │                     │           │ Ready for:          │                │
│  │ status: ROLLED_BACK │           │ - More imports      │                │
│  │                     │           │ - Attestation       │                │
│  └─────────────────────┘           └─────────────────────┘                │
│                                                │                           │
│                                                ▼                           │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ STEP 9: User attests CashFlow                                      │   │
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

### Wymagane zmiany przy wydzieleniu

1. **HTTP Client** zamiast bezpośredniego wywołania CommandGateway
2. **Kafka Events** dla asynchronicznej komunikacji
3. **Osobna baza MongoDB** dla kolekcji ingestion
4. **Service Discovery** (np. Eureka, Consul)

---

## Design Decisions

### 1. Import historyczny tylko przez system

**Decyzja:** Import historycznych transakcji odbywa się TYLKO przez bank-data-ingestion pipeline, nie przez bezpośrednie API.

**Uzasadnienie:**
- Spójność danych - system waliduje, deduplikuje, mapuje kategorie
- Audit trail - każdy import ma staging session, job ID
- Rollback - łatwo cofnąć cały import jako jednostkę
- UX - użytkownik nie musi ręcznie wklepywać setek transakcji

**Konsekwencje:**
- Endpoint `POST /cash-flow/{id}/import-historical` jest używany wewnętrznie przez bank-data-ingestion
- Użytkownik nie wywołuje `importHistoricalCashChange` bezpośrednio

---

### 2. Tworzenie kategorii przez import

**Decyzja:** W trybie SETUP użytkownik nie tworzy kategorii ręcznie - kategorie są tworzone automatycznie podczas importu na podstawie mapowań.

**Flow:**
1. Użytkownik uploaduje pliki CSV/Excel do wizard
2. System parsuje i pokazuje znalezione kategorie bankowe
3. Użytkownik mapuje każdą kategorię (CREATE_NEW, MAP_TO_UNCATEGORIZED)
4. System tworzy CashFlow + kategorie + importuje transakcje

**Konsekwencje:**
- `MAP_TO_EXISTING` ma sens głównie przy kolejnych importach (kategorie już istnieją)
- Pierwszy import używa głównie `CREATE_NEW`
- Endpoint `POST /cash-flow/{id}/category` w trybie SETUP może być zablokowany lub nieużywany w UI

---

### 3. Merge wielu plików CSV/Excel

**Decyzja:** Użytkownik może wgrać wiele plików na raz - system merguje je do jednego wsadu.

**Flow:**
```
Użytkownik wybiera pliki CSV/Excel (1-N)
         │
         ▼
    ┌─────────────────────────────┐
    │  Parser/Merger (Vidulum)    │
    │  - parsuje wszystkie pliki  │
    │  - deduplikuje po txId      │
    │  - sortuje po dacie         │
    │  - waliduje format          │
    └─────────────────────────────┘
         │
         ▼
    Jeden POST /staging
    { transactions: [...] }
```

**Uzasadnienie:**
- Prostszy model - jedna sesja staging zamiast wielu
- Deduplikacja między plikami
- Jeden preview pokazuje wszystko razem

---

### 4. Limity plików i transakcji

**Decyzja:** Ustalenie bezpiecznych limitów dla importu.

| Parametr | Limit | Uzasadnienie |
|----------|-------|--------------|
| Max rozmiar pliku | **20 MB** | Pokrywa 5+ lat historii |
| Max transakcji per staging | **20,000** | ~5 lat historii power usera |
| Max plików per upload | **10** | Rozsądny limit UX |
| Obsługiwane formaty | CSV, XLSX, XLS | Standardowe formaty bankowe |

**Szacunki rozmiaru:**

| Transakcji | Rozmiar JSON |
|------------|--------------|
| 1,000 | ~400 KB |
| 5,000 | ~2 MB |
| 10,000 | ~4 MB |
| 20,000 | ~8 MB |

**Typowa historia użytkownika:**

| Okres | Transakcji/miesiąc | Razem |
|-------|-------------------|-------|
| 1 rok | ~100 | ~1,200 |
| 3 lata | ~100 | ~3,600 |
| 5 lat | ~100 | ~6,000 |
| Power user (5 lat) | ~300/mies | ~18,000 |

**Konfiguracja:**

```yaml
vidulum:
  bank-data-ingestion:
    limits:
      max-file-size-mb: 20
      max-transactions-per-staging: 20000
      max-files-per-upload: 10
      allowed-extensions: [csv, xlsx, xls]
```

---

### 5. Źródła danych

**Obsługiwane źródła:**

| Źródło | Opis | Implementacja |
|--------|------|---------------|
| Pliki CSV | Export z banku | Parser CSV |
| Pliki Excel (XLSX/XLS) | Export z banku | Apache POI |
| Open Banking API | Automatyczny pobór | Future: OAuth2 + API client |

**Uwaga do Excel:** Apache POI ładuje cały plik do pamięci. Dla plików do 20MB (~20k transakcji) standardowe API wystarczy. Dla większych plików można użyć streaming API (`SXSSFWorkbook`).

---

## Changelog

| Data | Zmiany |
|------|--------|
| 2024-01-XX | Utworzenie dokumentu |
| 2024-01-XX | Szczegółowy opis kolekcji MongoDB |
| 2024-01-XX | Pełna specyfikacja REST API |
| 2024-01-XX | User Journey diagram |
| 2026-01-10 | Dodano sekcję Design Decisions: import przez system, kategorie przez import, merge plików, limity |
