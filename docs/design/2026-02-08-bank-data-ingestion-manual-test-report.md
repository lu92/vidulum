# Bank Data Ingestion Manual Test Report

**Date:** 2026-02-08
**Tester:** Claude Code
**Status:** CRITICAL BUG FOUND

---

## Test Summary

| Step | Operation | Status | Notes |
|------|-----------|--------|-------|
| 1 | Create CSV test files | PASS | 4 files created on Desktop |
| 2 | User registration | PASS | `ingestionuser01` created |
| 3 | Create CashFlow with history | PASS | startPeriod=2025-02, activePeriod=2026-02 |
| 4 | Upload CSV #1 (history) | PASS | 27 transactions staged |
| 5 | Map categories | PASS | 7 categories created |
| 6 | Import transactions | PASS | 27 transactions imported |
| 7 | Attestation (activate) | PASS | CashFlow status SETUP -> OPEN |
| 8 | Upload CSV #2 (gap fill) | **FAIL** | "Month is outside the CashFlow forecast range" |
| 9 | Upload CSV #3 (more history) | **FAIL** | Same error as #8 |
| 10 | Upload CSV #4 (current month) | **FAIL** | Same error as #8 |

---

## Test Environment

- **Backend:** Docker container `vidulum-app:latest`
- **Database:** MongoDB `testDB`
- **Test user:** `ingestionuser01` (MANAGER role)
- **CashFlow ID:** `2bb5cf45-578d-46cd-a8a9-7a0bf666563e`

---

## Test Data Files

| File | Location | Transactions | Months |
|------|----------|--------------|--------|
| `history_2025-02_to_2025-05.csv` | `~/Desktop/vidulum-test-csv/` | 27 | 2025-02 to 2025-05 |
| `history_2025-06_to_2025-09.csv` | `~/Desktop/vidulum-test-csv/` | 27 | 2025-06 to 2025-09 |
| `history_2025-10_to_2026-01.csv` | `~/Desktop/vidulum-test-csv/` | 27 | 2025-10 to 2026-01 |
| `current_2026-02.csv` | `~/Desktop/vidulum-test-csv/` | 5 | 2026-02 |

---

## Detailed Test Execution

### Step 1: Create CSV Test Files
```
Location: /Users/lucjanbik/Desktop/vidulum-test-csv/
Files created: 4
Total transactions: 86
```
Status: PASS

### Step 2: User Registration
```bash
POST /api/v1/auth/register
{
  "username": "ingestionuser01",
  "email": "ingestion01@test.com",
  "password": "TestPass123!",
  "role": "MANAGER"
}
```
Response: `access_token` and `refresh_token` returned
Status: PASS

### Step 3: Create CashFlow with History
```bash
POST /cash-flow/with-history
{
  "userId": "6988be7cbf0f27102d50802d",
  "name": "Bank Data Ingestion Test CashFlow",
  "startPeriod": "2025-02",
  "initialBalance": {"amount": 10000.00, "currency": "PLN"}
}
```
Response: `2bb5cf45-578d-46cd-a8a9-7a0bf666563e`
CashFlow created in SETUP mode with activePeriod=2026-02
Status: PASS

### Step 4: Upload First CSV File
```bash
POST /api/v1/bank-data-ingestion/{cashFlowId}/upload
File: history_2025-02_to_2025-05.csv
```
Response:
```json
{
  "parseSummary": {"totalRows": 27, "successfulRows": 27, "failedRows": 0},
  "stagingResult": {
    "stagingSessionId": "c22fc9f3-13f8-46bd-b155-bfd34f68648b",
    "status": "HAS_UNMAPPED_CATEGORIES",
    "unmappedCategories": [
      {"bankCategory": "Wpływy regularne", "count": 6, "type": "INFLOW"},
      {"bankCategory": "Mieszkanie", "count": 4, "type": "OUTFLOW"},
      {"bankCategory": "Zakupy kartą", "count": 5, "type": "OUTFLOW/INFLOW"},
      {"bankCategory": "Rachunki", "count": 4, "type": "OUTFLOW"},
      {"bankCategory": "Rozrywka", "count": 4, "type": "OUTFLOW"},
      {"bankCategory": "Transport", "count": 4, "type": "OUTFLOW"}
    ]
  }
}
```
Status: PASS

### Step 5: Map Categories
```bash
POST /api/v1/bank-data-ingestion/{cashFlowId}/mappings
```
Categories created:
- Salary (INFLOW)
- Refunds (INFLOW)
- Housing (OUTFLOW)
- Groceries (OUTFLOW)
- Bills (OUTFLOW)
- Entertainment (OUTFLOW)
- Transport (OUTFLOW)

Status: PASS

### Step 6: Import Transactions
```bash
POST /api/v1/bank-data-ingestion/{cashFlowId}/import
{"stagingSessionId": "c22fc9f3-13f8-46bd-b155-bfd34f68648b"}
```
Response:
```json
{
  "jobId": "9f3c4ea1-e3af-4ae6-a0a9-13ffb86be426",
  "status": "COMPLETED",
  "result": {
    "categoriesCreated": ["Salary", "Housing", "Groceries", "Bills", "Entertainment", "Transport", "Refunds"],
    "transactionsImported": 27,
    "transactionsFailed": 0
  },
  "canRollback": true
}
```
Status: PASS

### Step 7: Attestation (Activate CashFlow)
```bash
POST /cash-flow/{cashFlowId}/attest-historical-import
{"confirmedBalance": {"amount": 51917.01, "currency": "PLN"}}
```
Response:
```json
{
  "cashFlowId": "2bb5cf45-578d-46cd-a8a9-7a0bf666563e",
  "confirmedBalance": {"amount": 51917.01, "currency": "PLN"},
  "calculatedBalance": {"amount": 51917.01, "currency": "PLN"},
  "difference": {"amount": 0.00, "currency": "PLN"},
  "status": "OPEN"
}
```
CashFlow status changed from SETUP to OPEN
Status: PASS

### Step 8: Upload Second CSV File (Gap Fill) - FAILED
```bash
POST /api/v1/bank-data-ingestion/{cashFlowId}/upload
File: history_2025-06_to_2025-09.csv
```
Response:
```json
{
  "stagingResult": {
    "status": "HAS_VALIDATION_ERRORS",
    "summary": {"totalTransactions": 27, "validTransactions": 0, "invalidTransactions": 27}
  }
}
```
All transactions rejected with error:
```
"Month 2025-06 is outside the CashFlow forecast range (start: 2025-02)."
```
Status: **FAIL - CRITICAL BUG**

### Step 9: Upload Third CSV File - FAILED
Same error as Step 8 for months 2025-10 to 2026-01.
Status: **FAIL - SAME BUG**

### Step 10: Upload Current Month CSV - FAILED
```bash
POST /api/v1/bank-data-ingestion/{cashFlowId}/upload
File: current_2026-02.csv
```
Errors:
```
"Month 2026-02 is outside the CashFlow forecast range (start: 2025-02)."
"paidDate cannot be in the future" (for transaction dated 2026-02-10)
```
Status: **FAIL - SAME BUG + future date validation correct**

---

## CRITICAL BUG FOUND

### Root Cause Analysis

The bug is in the `HttpCashFlowServiceClient.java` and the REST API `GET /cash-flow/{id}` endpoint.

**Problem:**
1. The `CashFlowInfo` record expects `monthStatuses` map to determine which months allow import
2. The REST API response from `GET /cash-flow/{id}` does NOT include `monthStatuses` field
3. When `monthStatuses` is null/empty, `getMonthStatus(month)` returns null
4. When monthStatus is null, the validation treats ALL months as invalid

**Location of Bug:**
- `src/main/java/com/multi/vidulum/bank_data_ingestion/infrastructure/HttpCashFlowServiceClient.java:244`
- The `CashFlowResponse` record expects `monthStatuses` but the REST endpoint doesn't provide it

**Why it works in SETUP mode but fails in OPEN mode:**
- SETUP mode validation uses different logic (just checks if paidPeriod < activePeriod)
- OPEN mode validation requires monthStatuses to determine allowed months

### Fix Required

1. **Option A (Quick fix):** Add `monthStatuses` to `CashFlowRestController.getCashFlow()` response
   - Need to fetch month statuses from `CashFlowForecastProcessor`
   - Map month statuses to response DTO

2. **Option B (Alternative):** Create dedicated endpoint for bank-data-ingestion module
   - `GET /internal/cash-flow/{id}/import-info`
   - Returns only fields needed for import validation

### Impact
- **Gap filling after attestation: BLOCKED**
- **Ongoing sync (current month): BLOCKED**
- **Initial historical import (SETUP mode): WORKS**

---

## Other Findings

### 1. Future date validation works correctly
Transactions with `paidDate` in the future (e.g., 2026-02-10 when today is 2026-02-08) are correctly rejected with:
```
"paidDate cannot be in the future"
```

### 2. Category mapping works correctly
- Categories created from bank categories
- Mappings correctly applied to staged transactions
- Revalidation updates transaction statuses

### 3. Import job tracking works
- Job ID returned
- Progress tracking with phases
- Rollback capability available

---

## Recommendations

1. **HIGH PRIORITY:** Fix the monthStatuses bug to enable ongoing sync and gap filling
2. **MEDIUM:** Add integration test covering OPEN mode import
3. **LOW:** Consider adding dedicated API endpoint for bank-data-ingestion module

---

## Test Artifacts

### CashFlow State After Test
```json
{
  "cashFlowId": "2bb5cf45-578d-46cd-a8a9-7a0bf666563e",
  "status": "OPEN",
  "activePeriod": "2026-02",
  "startPeriod": "2025-02",
  "transactions": 27,
  "balance": {"amount": 51917.01, "currency": "PLN"}
}
```

### Categories Created
| Category | Type | Origin |
|----------|------|--------|
| Salary | INFLOW | USER_CREATED |
| Refunds | INFLOW | USER_CREATED |
| Housing | OUTFLOW | USER_CREATED |
| Groceries | OUTFLOW | USER_CREATED |
| Bills | OUTFLOW | USER_CREATED |
| Entertainment | OUTFLOW | USER_CREATED |
| Transport | OUTFLOW | USER_CREATED |

---

*Report generated: 2026-02-08*
*Next steps: Fix monthStatuses bug and re-run test*
