# Unified CSV Import - API Endpoints & Flow

## Overview

Simplified 3-step flow for CSV import with automatic format detection.

```
┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐
│  1. UPLOAD CSV      │────▶│  2. CREATE CASHFLOW │────▶│  3. MAP & IMPORT    │
│                     │     │                     │     │                     │
│  Auto-detect:       │     │  Pre-filled from:   │     │  Map categories     │
│  • CANONICAL        │     │  • detectedBank     │     │  Import transactions│
│  • CACHED           │     │  • suggestedStart   │     │  Done!              │
│  • AI_TRANSFORMED   │     │  • detectedCurrency │     │                     │
└─────────────────────┘     └─────────────────────┘     └─────────────────────┘
```

---

## Detection Types

| Type | Time | Cost | Description |
|------|------|------|-------------|
| `CANONICAL` | ~50ms | Free | Vidulum format CSV, no processing needed |
| `CACHED` | ~100ms | Free | Bank format seen before, uses cached rules |
| `AI_TRANSFORMED` | 5-15s | ~$0.01 | New bank format, AI creates mapping rules |

---

## Step 1: Upload CSV

### Endpoint

```
POST /api/v1/csv-import/upload
Content-Type: multipart/form-data
Authorization: Bearer {token}
```

### Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `file` | File | Yes | Bank CSV file (max 5MB) |
| `bankHint` | String | No | Bank name hint (e.g., "Nest Bank") |

### Response

```json
{
  "transformationId": "068859b0-bfba-4377-b9d5-51de43fd0587",
  "success": true,

  "detectionResult": "CACHED",
  "fromCache": true,
  "processingTimeMs": 85,

  "detectedBank": "Nest Bank",
  "detectedCurrency": "PLN",
  "detectedLanguage": "pl",
  "detectedCountry": "PL",

  "rowCount": 402,
  "minTransactionDate": "2023-01-13",
  "maxTransactionDate": "2025-12-31",
  "suggestedStartPeriod": "2023-01",
  "monthsOfData": 36,
  "monthsCovered": ["2023-01", "2023-02", ...],

  "bankCategories": [
    {"name": "Przelewy wychodzące", "count": 334, "type": "OUTFLOW"},
    {"name": "Przelewy przychodzące", "count": 52, "type": "INFLOW"},
    {"name": "Opłaty i prowizje", "count": 15, "type": "OUTFLOW"},
    {"name": "Płatności kartą", "count": 1, "type": "OUTFLOW"}
  ],

  "warnings": [],
  "importStatus": "PENDING"
}
```

### Key Fields for UI

| Field | UI Usage |
|-------|----------|
| `detectionResult` | Show processing type badge (instant vs AI) |
| `processingTimeMs` | Show processing time in success message |
| `detectedBank` | Pre-fill bank name in CashFlow form |
| `detectedCurrency` | Pre-fill currency (readonly) |
| `suggestedStartPeriod` | **CRITICAL**: Pre-fill and lock startPeriod |
| `minTransactionDate` | Show date range info |
| `maxTransactionDate` | Show date range info |
| `bankCategories` | Preview categories for mapping step |

---

## Step 2: Create CashFlow

### Endpoint

```
POST /cash-flow/with-history
Content-Type: application/json
Authorization: Bearer {token}
```

### Request

```json
{
  "userId": "U10000056",
  "name": "Nest Bank - Main Account",
  "description": "My primary bank account",
  "bankAccount": {
    "bankName": "Nest Bank",
    "bankAccountNumber": {
      "account": "PL61109010140000071219812874",
      "denomination": {"id": "PLN"}
    },
    "balance": {"amount": 0, "currency": "PLN"}
  },
  "startPeriod": "2023-01",
  "initialBalance": {"amount": 5000.00, "currency": "PLN"}
}
```

### Pre-fill Mapping

| Form Field | Source | Editable |
|------------|--------|----------|
| `name` | Suggest: `"{detectedBank} - Main Account"` | Yes |
| `bankAccount.bankName` | `detectedBank` | Yes |
| `bankAccount.denomination` | `detectedCurrency` | No |
| `startPeriod` | `suggestedStartPeriod` | **No** |
| `initialBalance` | **User must provide** | Yes |

### Response

```
"CF10000030"
```

(CashFlow ID string)

---

## Step 3: Map Categories & Import

### 3a. Import to Staging

```
POST /api/v1/bank-data-adapter/{transformationId}/import
Content-Type: application/json
Authorization: Bearer {token}
```

**Request:**
```json
{
  "cashFlowId": "CF10000030"
}
```

**Response:**
```json
{
  "transformationId": "068859b0-bfba-4377-b9d5-51de43fd0587",
  "stagingSessionId": "836658d6-aa69-4ede-a8c3-ac192fb779ee",
  "importedRows": 402,
  "message": "Transformation imported successfully"
}
```

### 3b. Get Unmapped Categories

```
GET /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{stagingSessionId}
Authorization: Bearer {token}
```

**Response:**
```json
{
  "stagingSessionId": "836658d6-aa69-4ede-a8c3-ac192fb779ee",
  "cashFlowId": "CF10000030",
  "status": "HAS_UNMAPPED_CATEGORIES",
  "unmappedCategories": [
    {"bankCategory": "Przelewy wychodzące", "count": 334, "type": "OUTFLOW"},
    {"bankCategory": "Opłaty i prowizje", "count": 15, "type": "OUTFLOW"},
    {"bankCategory": "Przelewy przychodzące", "count": 52, "type": "INFLOW"},
    {"bankCategory": "Płatności kartą", "count": 1, "type": "OUTFLOW"}
  ]
}
```

### 3c. Create Category Mappings

```
POST /api/v1/bank-data-ingestion/cf={cashFlowId}/mappings
Content-Type: application/json
Authorization: Bearer {token}
```

**Request:**
```json
{
  "mappings": [
    {
      "bankCategoryName": "Przelewy wychodzące",
      "action": "CREATE_NEW",
      "targetCategoryName": "Outgoing Transfers",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Przelewy przychodzące",
      "action": "CREATE_NEW",
      "targetCategoryName": "Income",
      "categoryType": "INFLOW"
    },
    {
      "bankCategoryName": "Opłaty i prowizje",
      "action": "CREATE_NEW",
      "targetCategoryName": "Bank Fees",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Płatności kartą",
      "action": "CREATE_NEW",
      "targetCategoryName": "Card Payments",
      "categoryType": "OUTFLOW"
    }
  ]
}
```

**Mapping Actions:**

| Action | Description |
|--------|-------------|
| `CREATE_NEW` | Create new category with `targetCategoryName` |
| `MAP_TO_EXISTING` | Map to existing CashFlow category |
| `SKIP` | Skip transactions with this bank category |

### 3d. Revalidate Staging

```
POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{stagingSessionId}/revalidate
Authorization: Bearer {token}
```

**Response:**
```json
{
  "stagingSessionId": "836658d6-aa69-4ede-a8c3-ac192fb779ee",
  "status": "SUCCESS",
  "summary": {
    "totalTransactions": 402,
    "validCount": 402,
    "invalidCount": 0
  },
  "stillUnmappedCategories": []
}
```

### 3e. Execute Import

```
POST /api/v1/bank-data-ingestion/cf={cashFlowId}/import
Content-Type: application/json
Authorization: Bearer {token}
```

**Request:**
```json
{
  "stagingSessionId": "836658d6-aa69-4ede-a8c3-ac192fb779ee"
}
```

**Response:**
```json
{
  "jobId": "b8044e93-de58-4761-8e4d-f5a52759587a",
  "cashFlowId": "CF10000030",
  "status": "COMPLETED",
  "progress": {
    "percentage": 100,
    "phases": [
      {"name": "CREATING_CATEGORIES", "status": "COMPLETED", "processed": 4, "total": 4},
      {"name": "IMPORTING_TRANSACTIONS", "status": "COMPLETED", "processed": 402, "total": 402}
    ]
  },
  "result": {
    "categoriesCreated": ["Outgoing Transfers", "Income", "Bank Fees", "Card Payments"],
    "transactionsImported": 402,
    "transactionsFailed": 0
  },
  "canRollback": true
}
```

---

## Step 4: Attest (Optional)

After import, CashFlow is in `SETUP` status. To transition to `OPEN`:

```
POST /cash-flow/cf={cashFlowId}/attest-historical-import
Content-Type: application/json
Authorization: Bearer {token}
```

**Request:**
```json
{
  "confirmedBalance": {"amount": 75184.31, "currency": "PLN"},
  "createAdjustment": false,
  "forceAttestation": false
}
```

---

## Error Handling

| HTTP Code | Error Code | Description | UI Action |
|-----------|------------|-------------|-----------|
| 400 | `AI_ADAPTER_EMPTY_FILE` | File is empty | Show error, ask for different file |
| 400 | `AI_ADAPTER_FILE_TOO_LARGE` | File > 5MB | Show size limit, ask for smaller file |
| 400 | `AI_ADAPTER_INVALID_FILE_TYPE` | Not a CSV | Show format requirement |
| 400 | `AI_ADAPTER_UNRECOGNIZED_FORMAT` | AI can't parse | Suggest canonical format |
| 409 | `AI_ADAPTER_DUPLICATE_FILE` | Same file uploaded before | Link to previous transformation |
| 429 | `AI_ADAPTER_RATE_LIMIT_EXCEEDED` | Too many AI requests | Show retry countdown |
| 503 | `AI_ADAPTER_AI_SERVICE_UNAVAILABLE` | AI service down | Show retry button |

---

## UI Flow Summary

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              STEP 1: UPLOAD                                  │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  [Drag & Drop Zone]                                                  │   │
│  │                                                                      │   │
│  │  Upload your bank's CSV file                                         │   │
│  │  Supported: ING, mBank, PKO BP, Nest Bank, Santander, BNP...         │   │
│  │                                                                      │   │
│  │  [Choose File]                                                       │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  Bank hint (optional): [ING] [mBank] [Nest] [Other]                          │
│                                                                              │
│  → POST /api/v1/csv-import/upload                                            │
│  → Response: detectionResult, suggestedStartPeriod, detectedBank...          │
└──────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           STEP 2: CREATE CASHFLOW                            │
│                                                                              │
│  ✅ CSV analyzed: CACHED (Nest Bank) • 402 transactions • 85ms               │
│                                                                              │
│  CashFlow Name:     [Nest Bank - Main Account          ]                     │
│  Bank Name:         [Nest Bank                 ] (detected)                  │
│  Currency:          [PLN                       ] (locked)                    │
│  Start Period:      [2023-01                   ] (from CSV, locked)          │
│  Initial Balance:   [___________] PLN ⚠️ (user must provide!)                │
│                                                                              │
│  → POST /cash-flow/with-history                                              │
│  → Response: cashFlowId                                                      │
└──────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          STEP 3: MAP & IMPORT                                │
│                                                                              │
│  Map bank categories to your categories:                                     │
│                                                                              │
│  ┌───────────────────────┬────────┬─────────────────────────────────────┐   │
│  │ Bank Category         │ Count  │ Map To                              │   │
│  ├───────────────────────┼────────┼─────────────────────────────────────┤   │
│  │ Przelewy wychodzące   │ 334    │ [+ Create: Outgoing Transfers    ▼] │   │
│  │ Przelewy przychodzące │ 52     │ [+ Create: Income                ▼] │   │
│  │ Opłaty i prowizje     │ 15     │ [+ Create: Bank Fees             ▼] │   │
│  │ Płatności kartą       │ 1      │ [+ Create: Card Payments         ▼] │   │
│  └───────────────────────┴────────┴─────────────────────────────────────┘   │
│                                                                              │
│  [Import 402 Transactions]                                                   │
│                                                                              │
│  → POST /api/v1/bank-data-adapter/{id}/import                                │
│  → POST /api/v1/bank-data-ingestion/cf={id}/mappings                         │
│  → POST /api/v1/bank-data-ingestion/cf={id}/staging/{id}/revalidate          │
│  → POST /api/v1/bank-data-ingestion/cf={id}/import                           │
└──────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              SUCCESS!                                        │
│                                                                              │
│  ✅ 402 transactions imported                                                │
│  ✅ 4 categories created                                                     │
│  ✅ 0 errors                                                                 │
│                                                                              │
│  [Import More Data]  [Go to CashFlow Dashboard →]                            │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Related Documentation

- [UI Mockups (HTML)](design/unified-csv-import-ui-mockups.html) - Visual mockups for Web & Mobile
- [Bank Data Ingestion Guide](BANK_DATA_INGESTION_GUIDE.md) - Full API reference
- [New User CSV Import Flow](NEW_USER_CSV_IMPORT_FLOW.md) - Detailed step-by-step guide
