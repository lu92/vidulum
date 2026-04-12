# New User CSV Import Flow - UI Integration Guide

Complete integration guide for the scenario: **New user without CashFlow imports bank CSV using AI transformation**.

## Flow Overview

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  1. Register    │────▶│  2. AI Transform │────▶│  3. Create      │
│     User        │     │     CSV          │     │     CashFlow    │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                                                          │
                                                          ▼
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  6. Start       │◀────│  5. Revalidate   │◀────│  4. Import to   │
│     Import      │     │     Staging      │     │     Staging     │
└─────────────────┘     └──────────────────┘     └─────────────────┘
         │                       ▲
         │              ┌────────┴────────┐
         │              │  Map Categories │
         │              │  (if needed)    │
         │              └─────────────────┘
         ▼
┌─────────────────┐
│  7. Verify      │
│     & Attest    │
└─────────────────┘
```

---

## Step 1: Register User

**Endpoint:** `POST /api/v1/auth/register`

**Request:**
```json
{
  "username": "newuser",
  "email": "newuser@example.com",
  "password": "SecurePassword123!"
}
```

**Response:**
```json
{
  "user_id": "U10000056",
  "access_token": "eyJhbGciOiJIUzM4NCJ9...",
  "refresh_token": "eyJhbGciOiJIUzM4NCJ9..."
}
```

**Important:** Store `access_token` for all subsequent requests and `user_id` for CashFlow creation.

---

## Step 2: AI Transform CSV

**Endpoint:** `POST /api/v1/bank-data-adapter/transform`

**Content-Type:** `multipart/form-data`

**Request:**
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `file` | File | Yes | Bank CSV file (max 5MB) |
| `bankHint` | String | No | Bank name hint (e.g., "Nest Bank", "mBank") |

**Example (curl):**
```bash
curl -X POST http://localhost:9090/api/v1/bank-data-adapter/transform \
  -H "Authorization: Bearer {access_token}" \
  -F "file=@lista_operacji.csv" \
  -F "bankHint=Nest Bank"
```

**Response:**
```json
{
  "transformationId": "068859b0-bfba-4377-b9d5-51de43fd0587",
  "success": true,
  "detectedBank": "Nest Bank",
  "detectedLanguage": "pl",
  "detectedCountry": "PL",
  "rowCount": 402,
  "warnings": [],
  "importStatus": "PENDING",
  "minTransactionDate": "2023-01-13",
  "maxTransactionDate": "2025-12-31",
  "suggestedStartPeriod": "2023-01",
  "monthsOfData": 36,
  "monthsCovered": ["2023-01", "2023-02", "2023-03", ...]
}
```

### ⚠️ CRITICAL: `suggestedStartPeriod`

The `suggestedStartPeriod` field is **essential** for CashFlow creation:

- It's derived from `minTransactionDate` (the earliest transaction in CSV)
- **UI MUST use this value** when creating CashFlow in step 3
- If user creates CashFlow with a later `startPeriod`, transactions before that date will be **REJECTED** during import

**Example:**
- CSV has transactions from `2023-01-13`
- `suggestedStartPeriod` = `"2023-01"`
- CashFlow MUST be created with `startPeriod: "2023-01"` or earlier

---

## Step 3: Create CashFlow with History

**Endpoint:** `POST /cash-flow/with-history`

**Request:**
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

### Field Details

| Field | Description |
|-------|-------------|
| `startPeriod` | **MUST match `suggestedStartPeriod`** from transform response |
| `initialBalance` | Bank balance at the START of `startPeriod` (before any transactions) |

**Response:** `CF10000030` (CashFlow ID string)

**CashFlow is created with:**
- `status: "SETUP"` - indicates historical import mode
- `activePeriod: "2026-03"` - automatically set to current month
- `startPeriod: "2023-01"` - as specified

---

## Step 4: Import Transformed CSV to Staging

**Endpoint:** `POST /api/v1/bank-data-adapter/{transformationId}/import`

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

**Important:** Store `stagingSessionId` for subsequent operations.

---

## Step 5: Check Staging Status & Map Categories

### 5a. Get Staging Session Status

**Endpoint:** `GET /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{stagingSessionId}`

**Response (when unmapped categories exist):**
```json
{
  "stagingSessionId": "836658d6-aa69-4ede-a8c3-ac192fb779ee",
  "cashFlowId": "CF10000030",
  "status": "HAS_UNMAPPED_CATEGORIES",
  "summary": {
    "totalTransactions": 402,
    "validTransactions": 0,
    "invalidTransactions": 0
  },
  "unmappedCategories": [
    {"bankCategory": "Przelewy wychodzące", "count": 334, "type": "OUTFLOW"},
    {"bankCategory": "Opłaty i prowizje", "count": 15, "type": "OUTFLOW"},
    {"bankCategory": "Przelewy przychodzące", "count": 52, "type": "INFLOW"},
    {"bankCategory": "Płatności kartą", "count": 1, "type": "OUTFLOW"}
  ]
}
```

### 5b. Create Category Mappings

**Endpoint:** `POST /api/v1/bank-data-ingestion/cf={cashFlowId}/mappings`

**Request:**
```json
{
  "mappings": [
    {
      "bankCategoryName": "Przelewy wychodzące",
      "action": "CREATE_NEW",
      "targetCategoryName": "Przelewy wychodzące",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Opłaty i prowizje",
      "action": "CREATE_NEW",
      "targetCategoryName": "Opłaty bankowe",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Przelewy przychodzące",
      "action": "CREATE_NEW",
      "targetCategoryName": "Przychody",
      "categoryType": "INFLOW"
    },
    {
      "bankCategoryName": "Płatności kartą",
      "action": "CREATE_NEW",
      "targetCategoryName": "Płatności kartą",
      "categoryType": "OUTFLOW"
    }
  ]
}
```

**Mapping Actions:**
| Action | Description |
|--------|-------------|
| `CREATE_NEW` | Create new category in CashFlow with `targetCategoryName` |
| `MAP_TO_EXISTING` | Map to existing CashFlow category |
| `SKIP` | Skip transactions with this bank category |

### 5c. Revalidate Staging Session

**Endpoint:** `POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{stagingSessionId}/revalidate`

**Response (success):**
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

**If `stillUnmappedCategories` is not empty, repeat 5b and 5c.**

---

## Step 6: Start Import Job

**Endpoint:** `POST /api/v1/bank-data-ingestion/cf={cashFlowId}/import`

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
    "categoriesCreated": ["Opłaty bankowe", "Przelewy wychodzące", "Przychody", "Płatności kartą"],
    "transactionsImported": 402,
    "transactionsFailed": 0
  },
  "canRollback": true
}
```

**For long imports, poll job status:**
`GET /api/v1/bank-data-ingestion/cf={cashFlowId}/import/{jobId}`

---

## Step 7: Verify & Attest

### 7a. Verify CashFlow Data

**Endpoint:** `GET /cash-flow/cf={cashFlowId}`

Verify:
- `status` = `"SETUP"` (still in setup mode until attested)
- `cashChanges` contains imported transactions

### 7b. Get Forecast

**Endpoint:** `GET /cash-flow-forecast/cf={cashFlowId}`

View forecasted balances and categorized transactions per month.

### 7c. Attest Historical Import

**Endpoint:** `POST /cash-flow/cf={cashFlowId}/attest-historical-import`

**Request:**
```json
{
  "confirmedBalance": {"amount": 75184.31, "currency": "PLN"},
  "createAdjustment": false,
  "forceAttestation": false
}
```

This transitions CashFlow from `SETUP` to `OPEN` status.

---

## UI Implementation Checklist

### Critical Requirements

- [ ] **Always use `suggestedStartPeriod`** from transform response when creating CashFlow
- [ ] Show user the date range of transactions (`minTransactionDate` to `maxTransactionDate`)
- [ ] Pre-fill CashFlow `startPeriod` with `suggestedStartPeriod` - do NOT allow later dates
- [ ] Ask user for `initialBalance` - this is the balance BEFORE the first transaction

### User Flow States

```
NEW_USER
  │
  ▼
UPLOAD_CSV ─────▶ AI_PROCESSING ─────▶ PREVIEW_RESULT
                                              │
                                              ▼
                              ┌─── CREATE_CASHFLOW_FORM
                              │     (pre-fill startPeriod!)
                              │               │
                              │               ▼
                              │         IMPORT_TO_STAGING
                              │               │
                              │               ▼
                              │         MAP_CATEGORIES ◀──┐
                              │               │           │
                              │               ▼           │
                              │         REVALIDATE ───────┘
                              │         (if unmapped)
                              │               │
                              │               ▼
                              │         IMPORT_RUNNING
                              │               │
                              │               ▼
                              │         IMPORT_COMPLETE
                              │               │
                              │               ▼
                              └───────▶ VERIFY_AND_ATTEST
                                              │
                                              ▼
                                        CASHFLOW_OPEN
```

### Error Handling

| Error | User Message | Action |
|-------|--------------|--------|
| `startPeriod` after `suggestedStartPeriod` | "Some transactions are dated before the start period and won't be imported" | Show warning, suggest using suggestedStartPeriod |
| `HAS_UNMAPPED_CATEGORIES` | "Please map bank categories to your categories" | Show mapping UI |
| Transactions before `startPeriod` | "X transactions rejected - dates before CashFlow start" | Prevent by using correct startPeriod |

---

## Example: Complete curl Session

```bash
# 1. Register
RESPONSE=$(curl -s -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","email":"test@example.com","password":"SecurePass123!"}')
TOKEN=$(echo $RESPONSE | jq -r '.access_token')
USER_ID=$(echo $RESPONSE | jq -r '.user_id')

# 2. Transform CSV
TRANSFORM=$(curl -s -X POST http://localhost:9090/api/v1/bank-data-adapter/transform \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@bank_export.csv" \
  -F "bankHint=Nest Bank")
TRANSFORMATION_ID=$(echo $TRANSFORM | jq -r '.transformationId')
START_PERIOD=$(echo $TRANSFORM | jq -r '.suggestedStartPeriod')

# 3. Create CashFlow (using suggestedStartPeriod!)
CF_ID=$(curl -s -X POST http://localhost:9090/cash-flow/with-history \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"userId\": \"$USER_ID\",
    \"name\": \"My Bank Account\",
    \"description\": \"Main account\",
    \"bankAccount\": {
      \"bankName\": \"Nest Bank\",
      \"bankAccountNumber\": {\"account\": \"PL61109010140000071219812874\", \"denomination\": {\"id\": \"PLN\"}},
      \"balance\": {\"amount\": 0, \"currency\": \"PLN\"}
    },
    \"startPeriod\": \"$START_PERIOD\",
    \"initialBalance\": {\"amount\": 5000, \"currency\": \"PLN\"}
  }")

# 4. Import to staging
STAGING=$(curl -s -X POST "http://localhost:9090/api/v1/bank-data-adapter/$TRANSFORMATION_ID/import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"cashFlowId\": \"$CF_ID\"}")
SESSION_ID=$(echo $STAGING | jq -r '.stagingSessionId')

# 5. Map categories
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf=$CF_ID/mappings" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "mappings": [
      {"bankCategoryName": "Przelewy wychodzące", "action": "CREATE_NEW", "targetCategoryName": "Transfers Out", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Przelewy przychodzące", "action": "CREATE_NEW", "targetCategoryName": "Income", "categoryType": "INFLOW"}
    ]
  }'

# 6. Revalidate
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf=$CF_ID/staging/$SESSION_ID/revalidate" \
  -H "Authorization: Bearer $TOKEN"

# 7. Start import
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf=$CF_ID/import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"stagingSessionId\": \"$SESSION_ID\"}"

# 8. Verify
curl -s "http://localhost:9090/cash-flow-forecast/cf=$CF_ID" \
  -H "Authorization: Bearer $TOKEN"
```

---

## Related Documentation

- [UI Integration Prompt](UI_INTEGRATION_PROMPT_AI_CSV_IMPORT.md) - Detailed UI component specs
- [Bank Data Ingestion Guide](BANK_DATA_INGESTION_GUIDE.md) - Full API reference
- [Historical Import User Guide](historical-import-user-guide.md) - User journey details
