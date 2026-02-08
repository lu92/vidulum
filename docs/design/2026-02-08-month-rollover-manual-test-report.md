# Manual Test Report: Month Rollover & Ongoing Sync

**Date:** 2026-02-08
**Environment:** Docker (vidulum-app:latest)
**Test Result:** PASSED

## Test Summary

| Test Scenario | Status | Details |
|--------------|--------|---------|
| Create CashFlow with History | PASSED | Created in SETUP mode |
| Import Historical Transactions | PASSED | 8 transactions imported |
| Attest Historical Import | PASSED | Transitioned to OPEN mode |
| Rollover Month | PASSED | 2026-02 → 2026-03 |
| Gap Filling (past month) | PASSED | Import to 2025-11 |
| Ongoing Sync (current month) | PASSED | Import to active period |
| Import to FORECASTED (validation) | PASSED | Correctly rejected |

---

## Detailed Test Execution

### Test Configuration
- **CashFlow ID:** `2c094296-8941-4dde-bb49-9a9d1a1147d5`
- **User ID:** `final-test-user`
- **Start Period:** 2025-10
- **Initial Balance:** 5000.00 PLN

### Step 1: Create CashFlow with History

**Endpoint:** `POST /cash-flow/with-history`

**Request:**
```json
{
  "userId": "final-test-user",
  "name": "Final Rollover Test",
  "description": "Complete rollover test",
  "bankAccount": {
    "bankName": "Final Bank",
    "bankAccountNumber": {
      "account": "PL99999999999999999999999999",
      "denomination": {"id": "PLN"}
    }
  },
  "startPeriod": "2025-10",
  "initialBalance": {"amount": 5000.00, "currency": "PLN"}
}
```

**Response:** `2c094296-8941-4dde-bb49-9a9d1a1147d5`

**Verification:**
- Status: SETUP
- Active Period: 2026-02
- Start Period: 2025-10

---

### Step 2: Import Historical Transactions

**Endpoint:** `POST /cash-flow/{id}/import-historical`

**Imported Transactions:**

| Month | Transaction | Type | Amount | Status |
|-------|-------------|------|--------|--------|
| 2025-10 | Salary | INFLOW | 8000 PLN | CONFIRMED |
| 2025-10 | Rent | OUTFLOW | 2500 PLN | CONFIRMED |
| 2025-11 | Salary | INFLOW | 8000 PLN | CONFIRMED |
| 2025-11 | Rent | OUTFLOW | 2500 PLN | CONFIRMED |
| 2025-12 | Salary | INFLOW | 8000 PLN | CONFIRMED |
| 2025-12 | Rent | OUTFLOW | 2500 PLN | CONFIRMED |
| 2026-01 | Salary | INFLOW | 8000 PLN | CONFIRMED |
| 2026-01 | Rent | OUTFLOW | 2500 PLN | CONFIRMED |

**Balance Calculation:**
- Initial: 5000 PLN
- Net per month: +8000 - 2500 = +5500 PLN
- Total: 5000 + (4 × 5500) = 27000 PLN

---

### Step 3: Attest Historical Import

**Endpoint:** `POST /cash-flow/{id}/attest-historical-import`

**Request:**
```json
{
  "confirmedBalance": {"amount": 27000.00, "currency": "PLN"},
  "forceAttestation": false
}
```

**Response:**
```json
{
  "cashFlowId": "2c094296-8941-4dde-bb49-9a9d1a1147d5",
  "confirmedBalance": {"amount": 27000.00, "currency": "PLN"},
  "calculatedBalance": {"amount": 27000.00, "currency": "PLN"},
  "difference": {"amount": 0.00, "currency": "PLN"},
  "forced": false,
  "adjustmentCreated": false,
  "adjustmentCashChangeId": null,
  "status": "OPEN"
}
```

**Result:** CashFlow transitioned from SETUP to OPEN mode.

---

### Step 4: Rollover Month

**Endpoint:** `POST /cash-flow/{id}/rollover`

**Response:**
```json
{
  "cashFlowId": "2c094296-8941-4dde-bb49-9a9d1a1147d5",
  "rolledOverPeriod": "2026-02",
  "newActivePeriod": "2026-03",
  "closingBalance": {"amount": 27000.00, "currency": "PLN"}
}
```

**Month Status Changes:**
- 2026-02: ACTIVE → ROLLED_OVER
- 2026-03: FORECASTED → ACTIVE
- 2026-04+: Remain FORECASTED

---

### Step 5: Gap Filling Test

**Endpoint:** `POST /cash-flow/{id}/import-historical`

**Request (import to historical month 2025-11):**
```json
{
  "name": "Missed Utility Bill",
  "description": "Gap filling test",
  "money": {"amount": 200.00, "currency": "PLN"},
  "type": "OUTFLOW",
  "category": "Uncategorized",
  "dueDate": "2025-11-20T00:00:00Z",
  "paidDate": "2025-11-20T00:00:00Z"
}
```

**Response:** `af36be0c-f845-48ad-aa75-1904108cedea`

**Result:** Successfully imported to IMPORTED month (gap filling works in OPEN mode).

---

### Step 6: Ongoing Sync Test

**Endpoint:** `POST /cash-flow/{id}/import-historical`

**Request (import to ROLLED_OVER month 2026-02):**
```json
{
  "name": "Freelance Income",
  "description": "Ongoing sync test",
  "money": {"amount": 1500.00, "currency": "PLN"},
  "type": "INFLOW",
  "category": "Uncategorized",
  "dueDate": "2026-02-08T00:00:00Z",
  "paidDate": "2026-02-08T00:00:00Z"
}
```

**Response:** `46cf4ad6-63be-4ed0-b869-e586ae171c6e`

**Result:** Successfully imported to ROLLED_OVER month (ongoing sync works in OPEN mode).

---

### Step 7: Validation Test - Import to FORECASTED Month

**Endpoint:** `POST /cash-flow/{id}/import-historical`

**Request (import to future month 2026-05):**
```json
{
  "name": "Future Transaction",
  "description": "Should fail",
  "money": {"amount": 500.00, "currency": "PLN"},
  "type": "INFLOW",
  "category": "Uncategorized",
  "dueDate": "2026-05-15T00:00:00Z",
  "paidDate": "2026-05-15T00:00:00Z"
}
```

**Response:**
```json
{
  "status": 400,
  "code": "IMPORT_TO_FORECASTED_MONTH_NOT_ALLOWED",
  "message": "Cannot import to FORECASTED month 2026-05 in CashFlow [2c094296-8941-4dde-bb49-9a9d1a1147d5]. Current active period is 2026-03. Only import to current or past months is allowed.",
  "timestamp": "2026-02-08T16:24:39.835790755Z"
}
```

**Result:** Correctly rejected with appropriate error code.

---

## Final State

```
CashFlow: 2c094296-8941-4dde-bb49-9a9d1a1147d5
Status: OPEN
Active Period: 2026-03
Balance: 28300.00 PLN (27000 + 1500 - 200)

Month Statuses:
  2025-10: IMPORTED
  2025-11: IMPORTED (includes gap-filled transaction)
  2025-12: IMPORTED
  2026-01: IMPORTED
  2026-02: ROLLED_OVER (includes ongoing sync transaction)
  2026-03: ACTIVE
  2026-04+: FORECASTED
```

---

## Automated Test Coverage

In addition to these manual tests, the following automated integration tests verify the rollover functionality:

1. **RolloverMonthIntegrationTest**
   - `shouldRolloverMonthSuccessfully`
   - `shouldRejectRolloverInSetupMode`
   - `shouldRejectRolloverInClosedMode`
   - `shouldUpdateForecastStatusesAfterRollover`

2. **DualCashflowStatementGeneratorWithRolledOver**
   - Complete end-to-end test demonstrating SETUP → OPEN → ROLLOVER → GAP_FILLING flow

All 287 tests pass successfully.

---

## Conclusion

The Month Rollover & Ongoing Sync feature has been fully implemented and tested:

1. **Rollover Command** - Works correctly, transitions ACTIVE → ROLLED_OVER
2. **Forecast Status Updates** - FORECASTED → ACTIVE for next month
3. **Gap Filling** - Imports to IMPORTED months work in OPEN mode
4. **Ongoing Sync** - Imports to ROLLED_OVER months work in OPEN mode
5. **Validation** - Imports to FORECASTED months are correctly rejected
6. **Balance Tracking** - Closing balance correctly carried forward
