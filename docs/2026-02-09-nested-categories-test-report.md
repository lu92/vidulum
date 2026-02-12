# Nested Categories Manual Test Report

**Date:** 2026-02-09
**Test User:** nestedtest2026
**CashFlow ID:** b16bfe48-cd20-4486-85c4-e8b9aafdd659

---

## Category Structure

```
INFLOW:
├── Uncategorized (SYSTEM)
│   └── ← Nieznany przelew (MAP_TO_UNCATEGORIZED)
├── Salary (CREATE_NEW) ← Wynagrodzenie
│   └── Bonus (CREATE_SUBCATEGORY) ← Premia
└── Tax Refund (CREATE_NEW) ← Zwrot podatku

OUTFLOW:
├── Uncategorized (SYSTEM)
│   └── ← Inne wydatki (MAP_TO_UNCATEGORIZED)
├── Housing (CREATE_NEW) ← Czynsz
│   └── Utilities (CREATE_SUBCATEGORY) ← Media
├── Groceries (CREATE_NEW) ← Zakupy spożywcze
│   └── Dining (CREATE_SUBCATEGORY) ← Restauracja
└── Transport (CREATE_NEW) ← Paliwo
    └── Parking (CREATE_SUBCATEGORY) ← Parking
```

---

## Test Summary

| Metric | Value |
|--------|-------|
| Total Transactions Imported | 63 |
| Categories Created | 9 (+ 2 system Uncategorized) |
| Nested Categories | 4 subcategories |
| Files Processed | 4 CSV files |
| Period Covered | 2025-05 to 2026-02 (10 months) |
| Initial Balance | 50,000.00 PLN |
| Final Balance | 177,318.50 PLN |

---

## Test Steps

### Step 1: User Registration

```bash
POST /api/v1/auth/register
```

**Request:**
```json
{
  "username": "nestedtest2026",
  "email": "nestedtest2026@test.com",
  "password": "Test123!",
  "role": "MANAGER"
}
```

**Result:** SUCCESS - Token received

---

### Step 2: Create CashFlow with History

```bash
POST /cash-flow/with-history
```

**Request:**
```json
{
  "userId": "nestedtest2026",
  "name": "Nested Categories Test CashFlow",
  "description": "Testing nested categories with CSV import",
  "bankAccount": {
    "bankName": "Test Bank",
    "bankAccountNumber": {
      "account": "PL99999999999999999999999999",
      "denomination": {"id": "PLN"}
    },
    "balance": {"amount": 0, "currency": "PLN"}
  },
  "startPeriod": "2025-05",
  "initialBalance": {"amount": 50000.00, "currency": "PLN"}
}
```

**Result:** SUCCESS
**CashFlow ID:** `b16bfe48-cd20-4486-85c4-e8b9aafdd659`
**Mode:** SETUP

---

### Step 3: Configure Category Mappings

```bash
POST /api/v1/bank-data-ingestion/{cashFlowId}/mappings
```

**Mappings Configured: 11**

| Bank Category | Action | Target Category | Parent | Type |
|--------------|--------|-----------------|--------|------|
| Wynagrodzenie | CREATE_NEW | Salary | - | INFLOW |
| Premia | CREATE_SUBCATEGORY | Bonus | Salary | INFLOW |
| Zwrot podatku | CREATE_NEW | Tax Refund | - | INFLOW |
| Nieznany przelew | MAP_TO_UNCATEGORIZED | Uncategorized | - | INFLOW |
| Czynsz | CREATE_NEW | Housing | - | OUTFLOW |
| Media | CREATE_SUBCATEGORY | Utilities | Housing | OUTFLOW |
| Zakupy spożywcze | CREATE_NEW | Groceries | - | OUTFLOW |
| Restauracja | CREATE_SUBCATEGORY | Dining | Groceries | OUTFLOW |
| Paliwo | CREATE_NEW | Transport | - | OUTFLOW |
| Parking | CREATE_SUBCATEGORY | Parking | Transport | OUTFLOW |
| Inne wydatki | MAP_TO_UNCATEGORIZED | Uncategorized | - | OUTFLOW |

**Result:** SUCCESS

---

### Step 4: Upload & Import File 1 (SETUP Mode)

**File:** `history_2025-05_to_2025-07.csv`
**Period:** 2025-05 to 2025-07
**Transactions:** 17

```bash
POST /api/v1/bank-data-ingestion/{cashFlowId}/upload
POST /api/v1/bank-data-ingestion/{cashFlowId}/import
POST /api/v1/bank-data-ingestion/{cashFlowId}/import/{jobId}/finalize
```

**Session ID:** `895bd9de-799b-4167-add4-468baca71783`
**Job ID:** `b9c14ac5-f870-4030-80cf-68c778ad31dd`

**Categories Created:**
- Salary, Bonus, Housing, Utilities, Groceries, Dining, Transport, Parking (8 total)

**Result:** SUCCESS - 17 transactions imported

---

### Step 5: Early Attestation Test

**Purpose:** Test if attestation works after only first file import

```bash
POST /cash-flow/{cashFlowId}/attest-historical-import
```

**Request:**
```json
{
  "confirmedBalance": {"amount": 87578.40, "currency": "PLN"},
  "forceActivation": false,
  "skipBalanceValidation": false
}
```

**Response:**
```json
{
  "cashFlowId": "b16bfe48-cd20-4486-85c4-e8b9aafdd659",
  "confirmedBalance": {"amount": 87578.40, "currency": "PLN"},
  "calculatedBalance": {"amount": 87578.40, "currency": "PLN"},
  "difference": {"amount": 0.00, "currency": "PLN"},
  "forced": false,
  "adjustmentCreated": false,
  "status": "OPEN"
}
```

**Result:** SUCCESS - CashFlow transitioned to OPEN mode

**Finding:** Early attestation works! CashFlow can be activated after partial history import.

---

### Step 6: Upload & Import File 2 (OPEN Mode - Historical Data)

**File:** `history_2025-08_to_2025-10.csv`
**Period:** 2025-08 to 2025-10
**Transactions:** 20

**Session ID:** `33adf985-8cbe-46b8-91e1-0b78f5c5e7b5`
**Job ID:** `74abd659-9052-4714-be74-95ade6e411ff`

**New Category Created:** Tax Refund

**Result:** SUCCESS - 20 transactions imported

**Finding:** Historical data can be imported even in OPEN mode!

---

### Step 7: Upload & Import File 3 (OPEN Mode)

**File:** `history_2025-11_to_2026-01.csv`
**Period:** 2025-11 to 2026-01
**Transactions:** 20

**Session ID:** `b55f3454-c441-44b8-85ba-a9b01db408fe`
**Job ID:** `892ce476-65b5-44bc-9e30-93fff1240020`

**Result:** SUCCESS - 20 transactions imported

---

### Step 8: Upload & Import File 4 (OPEN Mode - Current Month)

**File:** `current_2026-02.csv`
**Period:** 2026-02
**Transactions:** 6

**Session ID:** `51268409-a952-45db-9153-de72b54730f5`
**Job ID:** `dc97b2da-ff45-4a38-ad17-f00d8f058116`

**Result:** SUCCESS - 6 transactions imported

---

## Final Category Structure (Verified)

```
INFLOW CATEGORIES:
  Uncategorized (origin: SYSTEM)
  Salary (origin: USER_CREATED)
    └── Bonus (origin: USER_CREATED)
  Tax Refund (origin: USER_CREATED)

OUTFLOW CATEGORIES:
  Uncategorized (origin: SYSTEM)
  Housing (origin: USER_CREATED)
    └── Utilities (origin: USER_CREATED)
  Groceries (origin: USER_CREATED)
    └── Dining (origin: USER_CREATED)
  Transport (origin: USER_CREATED)
    └── Parking (origin: USER_CREATED)
```

---

## Transaction Breakdown by Category

| Type | Category | Transactions | Amount (PLN) |
|------|----------|--------------|--------------|
| INFLOW | Salary | 10 | 152,000.00 |
| INFLOW | Bonus (sub of Salary) | 3 | 17,500.00 |
| INFLOW | Tax Refund | 1 | 2,800.00 |
| INFLOW | Uncategorized | 2 | 650.00 |
| OUTFLOW | Housing | 10 | 32,200.00 |
| OUTFLOW | Utilities (sub of Housing) | 8 | 4,275.00 |
| OUTFLOW | Groceries | 10 | 4,604.00 |
| OUTFLOW | Dining (sub of Groceries) | 6 | 999.50 |
| OUTFLOW | Transport | 7 | 2,838.00 |
| OUTFLOW | Parking (sub of Transport) | 3 | 165.00 |
| OUTFLOW | Uncategorized | 3 | 550.00 |

---

## Monthly Breakdown

| Month | Inflow (PLN) | Outflow (PLN) | Net (PLN) | Transactions |
|-------|--------------|---------------|-----------|--------------|
| 2025-05 | 15,500.00 | 3,970.50 | +11,529.50 | 5 |
| 2025-06 | 19,500.00 | 4,123.30 | +15,376.70 | 6 |
| 2025-07 | 15,000.00 | 4,327.80 | +10,672.20 | 6 |
| 2025-08 | 17,800.00 | 4,741.60 | +13,058.40 | 7 |
| 2025-09 | 20,000.00 | 4,291.40 | +15,708.60 | 7 |
| 2025-10 | 15,000.00 | 4,736.40 | +10,263.60 | 6 |
| 2025-11 | 15,000.00 | 4,657.20 | +10,342.80 | 6 |
| 2025-12 | 23,000.00 | 5,975.00 | +17,025.00 | 8 |
| 2026-01 | 16,000.00 | 4,753.50 | +11,246.50 | 6 |
| 2026-02 | 16,150.00 | 4,054.80 | +12,095.20 | 6 |
| **TOTAL** | **172,950.00** | **45,631.50** | **+127,318.50** | **63** |

---

## Balance Summary

| Item | Amount (PLN) |
|------|--------------|
| Initial Balance | 50,000.00 |
| Total Inflows | +172,950.00 |
| Total Outflows | -45,631.50 |
| **Final Balance** | **177,318.50** |

---

## Key Findings

1. **Nested Categories Work Correctly**
   - Subcategories (Bonus, Utilities, Dining, Parking) are properly nested under parent categories
   - Origin tracking works (SYSTEM vs USER_CREATED)

2. **MAP_TO_UNCATEGORIZED Works**
   - Transactions with "Nieznany przelew" mapped to INFLOW Uncategorized
   - Transactions with "Inne wydatki" mapped to OUTFLOW Uncategorized

3. **Early Attestation Allowed**
   - CashFlow can be activated to OPEN mode after partial history import
   - Balance validation works correctly

4. **Historical Import in OPEN Mode**
   - Historical transactions (before activePeriod) can be imported even in OPEN mode
   - No date restriction preventing backfilling data

5. **Multi-File Import Successful**
   - 4 files imported successfully
   - Categories created only once, reused in subsequent imports
   - No duplicate transaction issues

---

## Login Credentials

| Field | Value |
|-------|-------|
| Username | `nestedtest2026` |
| Password | `Test123!` |
| CashFlow ID | `b16bfe48-cd20-4486-85c4-e8b9aafdd659` |

---

## Test Environment

- **Backend URL:** http://localhost:9090
- **MongoDB:** mongodb:27017
- **Kafka:** kafka:9092
- **Docker Containers:** vidulum-app, mongodb, kafka, kafdrop
