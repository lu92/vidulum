# Bank Data Ingestion - Curl Commands Reference (V2)

## Overview

This file contains all curl commands used during manual testing documented in `BANK_DATA_INGESTION_MANUAL_TEST_REPORT_V2.md`.

**Test Date:** 2026-01-24
**CashFlow IDs used:**
- CashFlow 1: `53650ff4-24fb-45af-a90e-58e07ead1428`
- CashFlow 2: `e6774a22-a632-4806-b8c5-e350575d86be`

---

## Environment Variables Setup

```bash
# Set these before running commands
export TOKEN="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJBZG1pbiIsImlhdCI6MTc2OTI5NDc4MSwiZXhwIjoxNzY5MzgxMTgxfQ.FBCkmee3YdQmcn84cNZKuNWlb_kIcBJ99AfL0ZnsQ1s"
export CASHFLOW_ID="53650ff4-24fb-45af-a90e-58e07ead1428"
export STAGING_ID="869c555f-96ab-46db-8019-6bd6db9718c1"
export JOB_ID="eafec475-19c6-4f86-8df9-c1ee44accf44"
export CASHFLOW_ID_2="e6774a22-a632-4806-b8c5-e350575d86be"
```

---

## Test Data - CSV File

Create `/tmp/historical-transactions.csv`:

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,paidDate
TXN-2025-07-001,July Salary,Monthly salary payment,Wpływy regularne,5000.00,PLN,INFLOW,2025-07-15
TXN-2025-07-002,July Rent,Monthly rent payment,Mieszkanie,1500.00,PLN,OUTFLOW,2025-07-15
TXN-2025-07-003,Biedronka,Zakupy spożywcze,Zakupy kartą,250.00,PLN,OUTFLOW,2025-07-25
TXN-2025-08-001,August Salary,Monthly salary payment,Wpływy regularne,5000.00,PLN,INFLOW,2025-08-15
TXN-2025-08-002,August Rent,Monthly rent payment,Mieszkanie,1500.00,PLN,OUTFLOW,2025-08-15
TXN-2025-08-003,Lidl,Zakupy spożywcze,Zakupy kartą,180.00,PLN,OUTFLOW,2025-08-20
TXN-2025-08-004,Electric Bill,Prąd sierpień,Rachunki,150.00,PLN,OUTFLOW,2025-08-25
TXN-2025-09-001,September Salary,Monthly salary payment,Wpływy regularne,5000.00,PLN,INFLOW,2025-09-15
TXN-2025-09-002,September Bonus,Quarterly bonus,Wpływy regularne,2000.00,PLN,INFLOW,2025-09-20
TXN-2025-09-003,September Rent,Monthly rent payment,Mieszkanie,1500.00,PLN,OUTFLOW,2025-09-15
TXN-2025-09-004,Auchan,Zakupy spożywcze,Zakupy kartą,200.00,PLN,OUTFLOW,2025-09-22
TXN-2025-10-001,October Salary,Monthly salary payment,Wpływy regularne,5200.00,PLN,INFLOW,2025-10-15
TXN-2025-10-002,October Rent,Monthly rent payment,Mieszkanie,1500.00,PLN,OUTFLOW,2025-10-15
TXN-2025-10-003,Cinema,Kino z rodziną,Rozrywka,150.00,PLN,OUTFLOW,2025-10-18
TXN-2025-10-004,Carrefour,Zakupy spożywcze,Zakupy kartą,250.00,PLN,OUTFLOW,2025-10-25
TXN-2025-11-001,November Salary,Monthly salary payment,Wpływy regularne,5200.00,PLN,INFLOW,2025-11-15
TXN-2025-11-002,November Rent,Monthly rent payment,Mieszkanie,1500.00,PLN,OUTFLOW,2025-11-15
TXN-2025-11-003,Uber,Przejazd Uberem,Transport,120.00,PLN,OUTFLOW,2025-11-20
TXN-2025-12-001,December Salary,Monthly salary payment,Wpływy regularne,5200.00,PLN,INFLOW,2025-12-15
TXN-2025-12-002,Christmas Bonus,Annual bonus,Wpływy regularne,3000.00,PLN,INFLOW,2025-12-20
TXN-2025-12-003,December Rent,Monthly rent payment,Mieszkanie,1500.00,PLN,OUTFLOW,2025-12-15
TXN-2025-12-004,Restaurant,Kolacja świąteczna,Rozrywka,400.00,PLN,OUTFLOW,2025-12-23
TXN-2025-12-005,Gas Bill,Gaz grudzień,Rachunki,150.00,PLN,OUTFLOW,2025-12-28
```

---

# SCENARIO 1: Basic Happy Path

## 1.1 Register New User

```bash
curl -s -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "manual_test_user",
    "password": "TestPass123",
    "role": "MANAGER"
  }'
```

**Note:** The `role` field is required. Without it, NPE occurs in backend.

---

## 1.2 Login and Get Token

```bash
curl -s -X POST http://localhost:9090/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "Admin",
    "password": "Admin"
  }'
```

Save the returned token:
```bash
export TOKEN="<accessToken_from_response>"
```

---

## 1.3 Create CashFlow with History

```bash
curl -s -X POST http://localhost:9090/cash-flow/with-history \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "Test CashFlow - Happy Path V2",
    "description": "Manual test for bank data import after bug fixes",
    "bankAccount": {
      "number": "PL12345678901234567890123456",
      "denomination": "PLN"
    },
    "startPeriod": "2025-07",
    "initialBalance": {"amount": 10000.00, "currency": "PLN"}
  }'
```

Save the returned CashFlowId:
```bash
export CASHFLOW_ID="<returned_uuid>"
```

---

## 1.4 Upload CSV File (without mappings)

```bash
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/historical-transactions.csv"
```

**Expected Response (after BUG-001 fix):**
- `stagingResult.status` = `"HAS_UNMAPPED_CATEGORIES"`
- `stagingResult.stagingSessionId` is returned AND persisted

Save the stagingSessionId:
```bash
export STAGING_ID="<stagingSessionId_from_response>"
```

---

## 1.5 Configure Category Mappings

```bash
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/mappings" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "mappings": [
      {"bankCategoryName": "Wpływy regularne", "action": "CREATE_NEW", "targetCategoryName": "Salary", "categoryType": "INFLOW"},
      {"bankCategoryName": "Mieszkanie", "action": "CREATE_NEW", "targetCategoryName": "Housing", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Zakupy kartą", "action": "CREATE_NEW", "targetCategoryName": "Groceries", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Rachunki", "action": "CREATE_NEW", "targetCategoryName": "Bills", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Rozrywka", "action": "CREATE_NEW", "targetCategoryName": "Entertainment", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Transport", "action": "CREATE_NEW", "targetCategoryName": "Transport", "categoryType": "OUTFLOW"}
    ]
  }'
```

---

## 1.6 Get Staging Preview (BUG-001 Critical Test)

```bash
curl -s -X GET "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/staging/${STAGING_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

**Before BUG-001 fix:** Returns `NOT_FOUND`
**After BUG-001 fix:** Returns `HAS_UNMAPPED_CATEGORIES` with transactions

---

## 1.6b Revalidate Staging (NEW FEATURE)

```bash
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/staging/${STAGING_ID}/revalidate" \
  -H "Authorization: Bearer $TOKEN"
```

**Expected Response:**
```json
{
  "status": "SUCCESS",
  "summary": {
    "totalTransactions": 23,
    "revalidatedCount": 23,
    "stillPendingCount": 0,
    "validCount": 23,
    "invalidCount": 0
  }
}
```

**Key benefit:** NO RE-UPLOAD REQUIRED after configuring mappings!

---

## 1.6c Get Staging Preview (after revalidation)

```bash
curl -s -X GET "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/staging/${STAGING_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

**Expected:** `status` = `"READY_FOR_IMPORT"`, 23 valid transactions

---

## 1.7 Start Import (BUG-002 Critical Test)

```bash
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/import" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"stagingSessionId\": \"${STAGING_ID}\"}"
```

**Before BUG-002 fix:** Error in logs `Cannot find cash-category with name...`
**After BUG-002 fix:** `status` = `"COMPLETED"`, 6 categories created, 23 transactions imported

Save the jobId:
```bash
export JOB_ID="<jobId_from_response>"
```

---

## 1.8 Get Import Progress

```bash
curl -s -X GET "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/import/${JOB_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 1.9 Verify CashFlow Forecast Statement

```bash
curl -s -X GET "http://localhost:9090/cash-flow-forecast/${CASHFLOW_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 1.10 Get CashFlow Details

```bash
curl -s -X GET "http://localhost:9090/cash-flow/${CASHFLOW_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

---

# SCENARIO 2: Different Mapping Strategies

## 2.1 Create Second CashFlow

```bash
curl -s -X POST http://localhost:9090/cash-flow/with-history \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "Test CashFlow - Mapping Strategies",
    "description": "Testing subcategories and different actions",
    "bankAccount": {
      "number": "PL98765432109876543210987654",
      "denomination": "PLN"
    },
    "startPeriod": "2025-07",
    "initialBalance": {"amount": 5000.00, "currency": "PLN"}
  }'
```

```bash
export CASHFLOW_ID_2="<returned_uuid>"
```

---

## 2.2 Configure Mappings with Subcategories

```bash
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID_2}/mappings" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "mappings": [
      {"bankCategoryName": "Wpływy regularne", "action": "CREATE_NEW", "targetCategoryName": "Regular Income", "categoryType": "INFLOW"},
      {"bankCategoryName": "Mieszkanie", "action": "CREATE_SUBCATEGORY", "targetCategoryName": "Rent", "parentCategoryName": "Housing", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Rachunki", "action": "CREATE_SUBCATEGORY", "targetCategoryName": "Utilities", "parentCategoryName": "Housing", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Zakupy kartą", "action": "CREATE_SUBCATEGORY", "targetCategoryName": "Food", "parentCategoryName": "Daily Expenses", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Rozrywka", "action": "CREATE_SUBCATEGORY", "targetCategoryName": "Fun", "parentCategoryName": "Daily Expenses", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Transport", "action": "MAP_TO_UNCATEGORIZED", "categoryType": "OUTFLOW"}
    ]
  }'
```

---

## 2.3 Upload CSV for CashFlow 2

```bash
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID_2}/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/historical-transactions.csv"
```

---

## 2.4 Revalidate and Import

```bash
# Revalidate
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID_2}/staging/<STAGING_ID_2>/revalidate" \
  -H "Authorization: Bearer $TOKEN"

# Import
curl -s -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID_2}/import" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"stagingSessionId": "<STAGING_ID_2>"}'
```

---

# SCENARIO 3: Post-Import Operations

## 3.1 Add Category in SETUP mode (should fail)

```bash
curl -s -X POST "http://localhost:9090/cash-flow/${CASHFLOW_ID}/category" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "category": "Savings",
    "type": "OUTFLOW"
  }'
```

**Expected:** 400 Bad Request with "Operation not allowed in SETUP mode"

---

## 3.2 Attest Historical Import

```bash
curl -s -X POST "http://localhost:9090/cash-flow/${CASHFLOW_ID}/attest-historical-import" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "confirmedBalance": {"amount": 34750.00, "currency": "PLN"},
    "forceAttestation": false,
    "createAdjustment": false
  }'
```

**Note:** Calculate confirmedBalance as:
- initialBalance (10000) + total inflows (35600) - total outflows (10850) = 34750 PLN

---

## 3.3 Add Category in OPEN mode (should work now)

```bash
curl -s -X POST "http://localhost:9090/cash-flow/${CASHFLOW_ID}/category" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "category": "Investments",
    "type": "OUTFLOW"
  }'
```

---

## 3.4 Add Expected Cash Change (INFLOW)

```bash
curl -s -X POST "http://localhost:9090/cash-flow/expected-cash-change" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{
    \"cashFlowId\": \"${CASHFLOW_ID}\",
    \"category\": \"Salary\",
    \"name\": \"Expected Bonus\",
    \"description\": \"Q1 2026 bonus\",
    \"money\": {\"amount\": 3000.00, \"currency\": \"PLN\"},
    \"type\": \"INFLOW\",
    \"dueDate\": \"2026-03-15T10:00:00Z\"
  }"
```

---

## 3.5 Add Paid Cash Change (OUTFLOW)

```bash
curl -s -X POST "http://localhost:9090/cash-flow/paid-cash-change" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{
    \"cashFlowId\": \"${CASHFLOW_ID}\",
    \"category\": \"Investments\",
    \"name\": \"Stock Purchase\",
    \"description\": \"Monthly investment\",
    \"money\": {\"amount\": 500.00, \"currency\": \"PLN\"},
    \"type\": \"OUTFLOW\",
    \"dueDate\": \"2026-01-20T10:00:00Z\",
    \"paidDate\": \"2026-01-20T10:00:00Z\"
  }"
```

---

## 3.6 Verify CashFlow Statement After Changes

```bash
curl -s -X GET "http://localhost:9090/cash-flow-forecast/${CASHFLOW_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

---

# SCENARIO 4: WebSocket Progress Monitoring

## 4.1 Check WebSocket Gateway Status

```bash
docker ps | grep websocket
```

## 4.2 Connect to WebSocket (using wscat)

```bash
# Install: npm install -g wscat
wscat -c ws://localhost:8081/events
```

After connection, send:
```json
{"type":"SUBSCRIBE","payload":{"cashFlowId":"<CASHFLOW_ID>","eventTypes":["IMPORT_PROGRESS"]}}
```

---

# LOG MONITORING

## Check for Errors

```bash
# All errors
docker logs vidulum-app 2>&1 | grep -E "ERROR|Exception"

# BUG-002 specific error (should NOT appear after fix)
docker logs vidulum-app 2>&1 | grep "Cannot find cash-category"

# Kafka issues
docker logs vidulum-app 2>&1 | grep -i kafka

# Follow logs in real-time
docker logs -f vidulum-app
```

---

# API Endpoints Quick Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/auth/register` | Register user |
| POST | `/api/v1/auth/login` | Login |
| POST | `/cash-flow/with-history` | Create CashFlow with history |
| POST | `/api/v1/bank-data-ingestion/{id}/upload` | Upload CSV |
| POST | `/api/v1/bank-data-ingestion/{id}/mappings` | Configure mappings |
| GET | `/api/v1/bank-data-ingestion/{id}/staging/{sid}` | Get staging preview |
| POST | `/api/v1/bank-data-ingestion/{id}/staging/{sid}/revalidate` | **NEW** Revalidate staging |
| POST | `/api/v1/bank-data-ingestion/{id}/import` | Start import |
| GET | `/api/v1/bank-data-ingestion/{id}/import/{jid}` | Get import progress |
| GET | `/cash-flow-forecast/{id}` | Get forecast statement |
| GET | `/cash-flow/{id}` | Get CashFlow details |
| POST | `/cash-flow/{id}/category` | Create category |
| POST | `/cash-flow/expected-cash-change` | Add expected transaction |
| POST | `/cash-flow/paid-cash-change` | Add paid transaction |
| POST | `/cash-flow/{id}/attest-historical-import` | Attest and transition to OPEN |
