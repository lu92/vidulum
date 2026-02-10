# Bank Data Ingestion - Manual Guide

This guide explains how to manually upload CSV files and configure category mappings in Vidulum.

## Prerequisites

- Running Vidulum backend (http://localhost:9090)
- User account with valid JWT token
- CSV files with bank transactions

## Overview

The bank data ingestion process consists of:

1. **Create CashFlow with History** - Creates a CashFlow in SETUP mode
2. **Configure Category Mappings** - Define how bank categories map to CashFlow categories
3. **Upload CSV Files** - Upload and stage transactions
4. **Import Transactions** - Process staged transactions
5. **Attest Historical Import** - Transition from SETUP to OPEN mode
6. **Import Current Month** - Import transactions in OPEN mode

---

## Step 1: Register/Login User

### Register New User

```bash
curl -X POST "http://localhost:9090/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "myuser",
    "email": "myuser@example.com",
    "password": "MyPassword123!",
    "role": "MANAGER"
  }'
```

**Response:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

### Login Existing User

```bash
curl -X POST "http://localhost:9090/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "myuser",
    "password": "MyPassword123!"
  }'
```

Save the `access_token` for subsequent requests:
```bash
TOKEN="eyJhbGciOiJIUzI1NiJ9..."
```

---

## Step 2: Create CashFlow with History

```bash
curl -X POST "http://localhost:9090/cash-flow/with-history" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "myuser",
    "name": "My CashFlow",
    "description": "Personal finances",
    "bankAccount": {
      "bankName": "My Bank",
      "bankAccountNumber": {
        "account": "PL12345678901234567890123456",
        "denomination": {"id": "PLN"}
      },
      "balance": {"amount": 0, "currency": "PLN"}
    },
    "startPeriod": "2025-05",
    "initialBalance": {"amount": 50000.00, "currency": "PLN"}
  }'
```

**Response:** CashFlow ID (UUID string)
```
c378cc5e-a0c9-4580-8d39-efb39e6ac13e
```

Save this ID:
```bash
CASHFLOW_ID="c378cc5e-a0c9-4580-8d39-efb39e6ac13e"
```

**Important:** `startPeriod` defines the earliest month for historical imports. All CSV transactions must have dates >= this period.

---

## Step 3: Configure Category Mappings

Before uploading CSV files, configure how bank categories should be mapped.

### Mapping Actions

| Action | Description |
|--------|-------------|
| `CREATE_NEW` | Create a new top-level category |
| `CREATE_SUBCATEGORY` | Create a subcategory under an existing parent |
| `MAP_TO_EXISTING` | Map to an existing category |
| `MAP_TO_UNCATEGORIZED` | Map to the default Uncategorized category |

### Example Mappings Configuration

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/mappings" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "mappings": [
      {
        "bankCategoryName": "Wynagrodzenie",
        "action": "CREATE_NEW",
        "targetCategoryName": "Salary",
        "categoryType": "INFLOW"
      },
      {
        "bankCategoryName": "Premia",
        "action": "CREATE_SUBCATEGORY",
        "targetCategoryName": "Bonus",
        "parentCategoryName": "Salary",
        "categoryType": "INFLOW"
      },
      {
        "bankCategoryName": "Nieznany przelew",
        "action": "MAP_TO_UNCATEGORIZED",
        "targetCategoryName": "Uncategorized",
        "categoryType": "INFLOW"
      },
      {
        "bankCategoryName": "Czynsz",
        "action": "CREATE_NEW",
        "targetCategoryName": "Housing",
        "categoryType": "OUTFLOW"
      },
      {
        "bankCategoryName": "Media",
        "action": "CREATE_SUBCATEGORY",
        "targetCategoryName": "Utilities",
        "parentCategoryName": "Housing",
        "categoryType": "OUTFLOW"
      }
    ]
  }'
```

**Response:**
```json
{
  "cashFlowId": "c378cc5e-...",
  "mappingsConfigured": 5,
  "mappings": [
    {
      "mappingId": "598b18fc-...",
      "bankCategoryName": "Wynagrodzenie",
      "targetCategoryName": "Salary",
      "action": "CREATE_NEW",
      "status": "CREATED"
    },
    ...
  ]
}
```

### Get Current Mappings

```bash
curl "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/mappings" \
  -H "Authorization: Bearer $TOKEN"
```

### Update/Delete Mappings

```bash
# Delete a mapping
curl -X DELETE "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/mappings/{mappingId}" \
  -H "Authorization: Bearer $TOKEN"
```

---

## Step 4: Upload CSV File

### CSV Format

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
TRX-2025-05-001,Salary May,Monthly salary,Wynagrodzenie,15000.00,PLN,INFLOW,2025-05-10,2025-05-10,PL111...,PL999...
TRX-2025-05-002,Rent May,Monthly rent,Czynsz,3200.00,PLN,OUTFLOW,2025-05-05,2025-05-05,PL999...,PL222...
```

**Required columns:**
- `bankTransactionId` - Unique ID from bank
- `name` - Transaction name
- `bankCategory` - Category from bank (used for mapping)
- `amount` - Transaction amount
- `currency` - Currency code (PLN, EUR, USD)
- `type` - INFLOW or OUTFLOW
- `operationDate` - Transaction date (YYYY-MM-DD)

**Optional columns:**
- `description` - Additional description
- `bookingDate` - Booking date
- `sourceAccountNumber` - Source account
- `targetAccountNumber` - Target account

### Upload Request

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/transactions.csv"
```

**Response:**
```json
{
  "parseSummary": {
    "totalRows": 17,
    "successfulRows": 17,
    "failedRows": 0
  },
  "stagingResult": {
    "stagingSessionId": "b7579eb9-57cf-4d25-8336-0499bc4b51a5",
    "status": "READY_FOR_IMPORT",
    "summary": {
      "totalTransactions": 17,
      "validTransactions": 17,
      "invalidTransactions": 0,
      "duplicateTransactions": 0
    },
    "categoriesToCreate": [
      {"name": "Salary", "parent": null, "type": "INFLOW"},
      {"name": "Bonus", "parent": "Salary", "type": "INFLOW"},
      {"name": "Housing", "parent": null, "type": "OUTFLOW"}
    ],
    "unmappedCategories": []
  }
}
```

Save the staging session ID:
```bash
SESSION_ID="b7579eb9-57cf-4d25-8336-0499bc4b51a5"
```

### Staging Statuses

| Status | Description | Action Required |
|--------|-------------|-----------------|
| `READY_FOR_IMPORT` | All transactions valid, ready to import | Proceed to import |
| `HAS_UNMAPPED_CATEGORIES` | Some bank categories not mapped | Configure mappings, then revalidate |
| `HAS_VALIDATION_ERRORS` | Some transactions have errors | Check invalid transactions |

### Handle Unmapped Categories

If status is `HAS_UNMAPPED_CATEGORIES`:

1. Check unmapped categories in response
2. Add missing mappings (Step 3)
3. Revalidate staging:

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/staging/$SESSION_ID/revalidate" \
  -H "Authorization: Bearer $TOKEN"
```

---

## Step 5: Import Transactions

### Start Import

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"stagingSessionId\": \"$SESSION_ID\"}"
```

**Response:**
```json
{
  "jobId": "3515f7ea-0fb8-4558-b4b9-22bb49f07ef4",
  "status": "COMPLETED",
  "result": {
    "categoriesCreated": ["Salary", "Bonus", "Housing"],
    "transactionsImported": 17,
    "transactionsFailed": 0
  },
  "canRollback": true
}
```

Save the job ID:
```bash
JOB_ID="3515f7ea-0fb8-4558-b4b9-22bb49f07ef4"
```

### Check Import Progress (for long imports)

```bash
curl "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/import/$JOB_ID" \
  -H "Authorization: Bearer $TOKEN"
```

### Finalize Import

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/import/$JOB_ID/finalize" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"rollback": false}'
```

**Response:**
```json
{
  "jobId": "3515f7ea-...",
  "status": "FINALIZED",
  "finalSummary": {
    "categoriesCreated": 3,
    "transactionsImported": 17
  }
}
```

### Rollback Import (if needed)

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/import/$JOB_ID/finalize" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"rollback": true}'
```

---

## Step 6: Repeat for Additional Files

Repeat Steps 4-5 for each historical CSV file.

---

## Step 7: Attest Historical Import

After importing all historical data, attest to transition from SETUP to OPEN mode.

### Calculate Expected Balance

Get CashFlow to calculate balance:
```bash
curl "http://localhost:9090/cash-flow/$CASHFLOW_ID" \
  -H "Authorization: Bearer $TOKEN"
```

Calculate: `initialBalance + totalInflows - totalOutflows`

### Attest

```bash
curl -X POST "http://localhost:9090/cash-flow/$CASHFLOW_ID/attest-historical-import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "confirmedBalance": {"amount": 165223.30, "currency": "PLN"},
    "forceActivation": false,
    "skipBalanceValidation": false
  }'
```

**Response:**
```json
{
  "cashFlowId": "c378cc5e-...",
  "confirmedBalance": {"amount": 165223.30, "currency": "PLN"},
  "calculatedBalance": {"amount": 165223.30, "currency": "PLN"},
  "difference": {"amount": 0.00, "currency": "PLN"},
  "status": "OPEN"
}
```

**Options:**
- `forceActivation: true` - Force activation even with balance mismatch (creates adjustment)
- `skipBalanceValidation: true` - Skip balance check entirely

---

## Step 8: Import Current Month (OPEN Mode)

After attestation, CashFlow is in OPEN mode. You can now import current month transactions:

```bash
# Upload
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/current_month.csv"

# Import (same as before)
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"stagingSessionId\": \"$SESSION_ID\"}"

# Finalize
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/import/$JOB_ID/finalize" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"rollback": false}'
```

---

## Validation Rules

### Transaction Date Validation

| CashFlow Mode | Allowed Dates |
|---------------|---------------|
| SETUP | >= startPeriod AND < activePeriod AND <= today |
| OPEN | >= activePeriod AND <= today |

### Common Errors

| Error | Cause | Solution |
|-------|-------|----------|
| `paidDate is before startPeriod` | Transaction date before CashFlow start | Use correct startPeriod or remove transaction |
| `paidDate cannot be in the future` | Transaction date > today | Wait or change date |
| `outside forecast range` | Date not in valid range for mode | Check CashFlow mode and date ranges |

---

## Category Structure

### Nested Categories Example

```
INFLOW:
├── Uncategorized (system, always exists)
├── Salary (CREATE_NEW)
│   └── Bonus (CREATE_SUBCATEGORY, parent: Salary)
└── Tax Refund (CREATE_NEW)

OUTFLOW:
├── Uncategorized (system, always exists)
├── Housing (CREATE_NEW)
│   └── Utilities (CREATE_SUBCATEGORY, parent: Housing)
├── Groceries (CREATE_NEW)
│   └── Dining (CREATE_SUBCATEGORY, parent: Groceries)
└── Transport (CREATE_NEW)
    └── Parking (CREATE_SUBCATEGORY, parent: Transport)
```

---

## Quick Reference - Full Flow Script

```bash
#!/bin/bash

# Configuration
TOKEN="your_jwt_token"
CASHFLOW_ID="your_cashflow_id"

# 1. Configure mappings
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/mappings" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d @mappings.json

# 2. Upload CSV
UPLOAD_RESP=$(curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@transactions.csv")

SESSION_ID=$(echo $UPLOAD_RESP | jq -r '.stagingResult.stagingSessionId')
echo "Session: $SESSION_ID"

# 3. Import
IMPORT_RESP=$(curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"stagingSessionId\": \"$SESSION_ID\"}")

JOB_ID=$(echo $IMPORT_RESP | jq -r '.jobId')
echo "Job: $JOB_ID"

# 4. Finalize
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/import/$JOB_ID/finalize" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"rollback": false}'

echo "Done!"
```

---

## Troubleshooting

### CashFlow not visible after login

Check if CashFlow exists:
```bash
curl "http://localhost:9090/cash-flow/$CASHFLOW_ID" \
  -H "Authorization: Bearer $TOKEN"
```

If empty, MongoDB may have lost data (no persistence configured).

### Token expired

Get new token:
```bash
curl -X POST "http://localhost:9090/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"myuser","password":"MyPassword123!"}'
```

### Import stuck

Check import status:
```bash
curl "http://localhost:9090/api/v1/bank-data-ingestion/$CASHFLOW_ID/import/$JOB_ID" \
  -H "Authorization: Bearer $TOKEN"
```
