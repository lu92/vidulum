# Validation Issues & Findings Report

**Date:** 2026-02-08
**Status:** TO BE FIXED
**Priority:** Medium-High

---

## Executive Summary

During the implementation and testing of Month Rollover & Ongoing Sync feature, several validation issues and potential bugs were identified. This document catalogs all findings for future remediation.

**Key Statistics:**
- 1 critical bug (FIXED)
- 11 REST endpoints missing `@Valid` annotation
- 10+ DTOs missing validation annotations
- 6+ potential NullPointerException risks in event handlers
- 4+ places using generic exceptions instead of domain exceptions

---

## 1. FIXED BUG: NullPointerException in CashFlowWithHistoryCreatedEventHandler

| Attribute | Details |
|-----------|---------|
| **File** | `src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/processing/CashFlowWithHistoryCreatedEventHandler.java` |
| **Line** | 42 |
| **Problem** | `event.bankAccount().balance()` returned `null` |
| **Root Cause** | `BankAccount.balance` is not set during CashFlow creation with history |
| **Impact** | Every `CashFlowWithHistoryCreatedEvent` caused NPE, blocking entire Kafka pipeline |
| **Fix Applied** | Changed to `event.initialBalance()` |
| **Status** | ✅ **FIXED** |

```java
// BEFORE (bug):
Money currency = event.bankAccount().balance();

// AFTER (fixed):
Money currency = event.initialBalance();
```

---

## 2. Missing @Valid Annotations on REST Endpoints

**File:** `src/main/java/com/multi/vidulum/cashflow/app/CashFlowRestController.java`

| Line | Endpoint | Method Parameter | Issue |
|------|----------|------------------|-------|
| 91-108 | `POST /{cashFlowId}/import-historical` | `ImportHistoricalCashChangeJson` | Missing `@Valid` |
| 115-160 | `POST /{cashFlowId}/attest-historical-import` | `AttestHistoricalImportJson` | Missing `@Valid` |
| 167-190 | `DELETE /{cashFlowId}/import` | `RollbackImportJson` | Missing `@Valid` |
| 192-208 | `POST /expected-cash-change` | `AppendExpectedCashChangeJson` | Missing `@Valid` |
| 210-227 | `POST /paid-cash-change` | `AppendPaidCashChangeJson` | Missing `@Valid` |
| 288-313 | `POST /{cashFlowId}/category` | `CreateCategoryJson` | Missing `@Valid` |
| 323-333 | `POST /budgeting` | `SetBudgetingJson` | Missing `@Valid` |
| 335-345 | `PUT /budgeting` | `UpdateBudgetingJson` | Missing `@Valid` |
| 347-356 | `DELETE /budgeting` | `RemoveBudgetingJson` | Missing `@Valid` |
| 362-374 | `POST /{cashFlowId}/category/archive` | `ArchiveCategoryJson` | Missing `@Valid` |
| 379-390 | `POST /{cashFlowId}/category/unarchive` | `UnarchiveCategoryJson` | Missing `@Valid` |

**Impact:** Input validation is bypassed at REST layer, allowing invalid requests to reach domain handlers.

**Recommended Fix:**
```java
// BEFORE:
@PostMapping("/{cashFlowId}/import-historical")
public String importHistoricalCashChange(
        @PathVariable String cashFlowId,
        @RequestBody ImportHistoricalCashChangeJson request) {

// AFTER:
@PostMapping("/{cashFlowId}/import-historical")
public String importHistoricalCashChange(
        @PathVariable String cashFlowId,
        @Valid @RequestBody ImportHistoricalCashChangeJson request) {
```

---

## 3. Missing Validation Annotations on DTOs

**File:** `src/main/java/com/multi/vidulum/cashflow/app/CashFlowDto.java`

### 3.1 AppendExpectedCashChangeJson (lines 206-216)

| Field | Current | Required |
|-------|---------|----------|
| `cashFlowId` | none | `@NotBlank` |
| `category` | none | `@NotBlank` |
| `name` | none | `@NotBlank` |
| `money` | none | `@NotNull @Valid` |
| `type` | none | `@NotNull` |
| `dueDate` | none | `@NotNull` |

### 3.2 AppendPaidCashChangeJson (lines 218-229)

| Field | Current | Required |
|-------|---------|----------|
| `cashFlowId` | none | `@NotBlank` |
| `category` | none | `@NotBlank` |
| `name` | none | `@NotBlank` |
| `money` | none | `@NotNull @Valid` |
| `type` | none | `@NotNull` |
| `dueDate` | none | `@NotNull` |
| `paidDate` | none | `@NotNull` |

### 3.3 ImportHistoricalCashChangeJson (lines 235-245)

| Field | Current | Required |
|-------|---------|----------|
| `category` | none | `@NotBlank` |
| `name` | none | `@NotBlank` |
| `money` | none | `@NotNull @Valid` |
| `type` | none | `@NotNull` |
| `dueDate` | none | `@NotNull` |
| `paidDate` | none | `@NotNull` |

### 3.4 AttestHistoricalImportJson (lines 251-260)

| Field | Current | Required |
|-------|---------|----------|
| `confirmedBalance` | none | `@NotNull @Valid` |

### 3.5 CreateCategoryJson (lines 411-417)

| Field | Current | Required |
|-------|---------|----------|
| `category` | none | `@NotBlank` |
| `type` | none | `@NotNull` |

### 3.6 SetBudgetingJson (lines 419-426)

| Field | Current | Required |
|-------|---------|----------|
| `cashFlowId` | none | `@NotBlank` |
| `categoryName` | none | `@NotBlank` |
| `categoryType` | none | `@NotNull` |
| `budget` | none | `@NotNull @Valid` |

### 3.7 UpdateBudgetingJson (lines 428-435)

| Field | Current | Required |
|-------|---------|----------|
| `cashFlowId` | none | `@NotBlank` |
| `categoryName` | none | `@NotBlank` |
| `categoryType` | none | `@NotNull` |
| `newBudget` | none | `@NotNull @Valid` |

### 3.8 RemoveBudgetingJson (lines 437-443)

| Field | Current | Required |
|-------|---------|----------|
| `cashFlowId` | none | `@NotBlank` |
| `categoryName` | none | `@NotBlank` |
| `categoryType` | none | `@NotNull` |

### 3.9 ArchiveCategoryJson (lines 448-458)

| Field | Current | Required |
|-------|---------|----------|
| `categoryName` | none | `@NotBlank` |
| `categoryType` | none | `@NotNull` |

### 3.10 UnarchiveCategoryJson (lines 463-468)

| Field | Current | Required |
|-------|---------|----------|
| `categoryName` | none | `@NotBlank` |
| `categoryType` | none | `@NotNull` |

---

## 4. Potential NullPointerException Risks in Event Handlers

**Path:** `src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/processing/`

| File | Line | Code | Risk |
|------|------|------|------|
| `MonthRolledOverEventHandler.java` | 64 | `statement.getForecasts().get(actualPeriod)` | Returns null if period not in map |
| `MonthRolledOverEventHandler.java` | 65 | `statement.getForecasts().get(event.newActivePeriod())` | Returns null if period not in map |
| `MonthRolledOverEventHandler.java` | 109 | `cashCategory.getGroupedTransactions().get(EXPECTED)` | Returns null if EXPECTED key not present |
| `HistoricalCashChangeImportedEventHandler.java` | 156 | `cashCategory.getGroupedTransactions().get(PAID)` | Returns null if PAID key not present |
| `ExpectedCashChangeAppendedEventHandler.java` | ~56 | `.get(EXPECTED)` | Returns null if EXPECTED key not present |
| `PaidCashChangeAppendedEventHandler.java` | ~62 | `.get(PAID)` | Returns null if PAID key not present |
| `MonthAttestedEventHandler.java` | 37, 39, 63 | `getForecasts().get(period)` | Returns null if period not in map |
| `CashChangeConfirmedEventHandler.java` | ~28 | `location.transaction()` | Not null-checked |

**Recommended Fix Pattern:**
```java
// BEFORE (risky):
CashFlowMonthlyForecast forecast = statement.getForecasts().get(period);
forecast.setStatus(...); // NPE if forecast is null

// AFTER (safe):
CashFlowMonthlyForecast forecast = statement.getForecasts().get(period);
if (forecast == null) {
    throw new IllegalStateException(
        String.format("Forecast for period [%s] not found in statement [%s]",
            period, statement.getCashFlowId()));
}
forecast.setStatus(...);

// OR using Optional:
statement.getForecasts()
    .getOrDefault(period, Collections.emptyList())
    .forEach(...);
```

---

## 5. Generic Exceptions Instead of Domain Exceptions

| File | Line | Current Exception | Suggested Domain Exception |
|------|------|-------------------|---------------------------|
| `CashFlowRestController.java` | 458 | `IllegalArgumentException` | `InvalidTargetPeriodException` |
| `MonthRolledOverEventHandler.java` | 52 | `IllegalArgumentException` | `InvalidRolloverPeriodException` |
| `MonthRolledOverEventHandler.java` | 59 | `IllegalArgumentException` | `InvalidRolloverPeriodException` |
| `ExpectedCashChangeAppendedEventHandler.java` | 32, 45 | `IllegalStateException` | `CategoryNotFoundException` |
| `PaidCashChangeAppendedEventHandler.java` | 32, 45 | `IllegalStateException` | `CategoryNotFoundException` |
| `MonthAttestedEventHandler.java` | 34 | `IllegalStateException` | `ForecastNotFoundException` |
| `HistoricalCashChangeImportedEventHandler.java` | 117-118 | `IllegalStateException` | `ForecastPeriodNotFoundException` |
| `HistoricalCashChangeImportedEventHandler.java` | 124-125 | `IllegalStateException` | `CategoryNotFoundException` |

**Benefits of Domain Exceptions:**
1. Can be caught by `ErrorHttpHandler` and mapped to proper HTTP responses
2. Provide better error codes for API clients
3. Easier to distinguish between business errors and programming errors
4. Better logging and monitoring categorization

---

## 6. Validation Happening Too Late (in Handlers Instead of REST Layer)

Some validations are performed in command handlers instead of at the REST layer:

| Validation | Current Location | Better Location |
|------------|------------------|-----------------|
| Future paidDate check | `AppendPaidCashChangeCommandHandler:29` | REST controller or DTO validation |
| SETUP mode check | `AppendExpectedCashChangeCommandHandler:30` | Could be checked earlier in controller |
| DueDate range validation | `AppendExpectedCashChangeCommandHandler:51` | REST controller or DTO validation |
| Archived category check | `AppendExpectedCashChangeCommandHandler:45` | Could be service layer |
| Import date validations | `ImportHistoricalCashChangeCommandHandler:38-70` | REST controller |

**Impact:**
- Validation errors return as 500 (caught by general handler) instead of 400
- Error messages may expose internal implementation details
- Harder to provide consistent error responses

---

## 7. Prioritized Action Plan

### Priority 1 - HIGH (Security & Stability)
1. Add `@Valid` to all 11 REST endpoints
2. Add validation annotations to all DTOs
3. Add null-safety checks in critical event handlers

### Priority 2 - MEDIUM (Maintainability)
4. Create domain-specific exceptions for all `IllegalArgumentException`/`IllegalStateException` cases
5. Add proper error handlers in `ErrorHttpHandler.java` for new exceptions
6. Add new error codes in `ErrorCode.java`

### Priority 3 - LOW (Code Quality)
7. Move validation logic from handlers to REST layer where appropriate
8. Add comprehensive unit tests for validation scenarios
9. Document expected error responses in API documentation

---

## 8. Testing Checklist After Fixes

After implementing fixes, verify:

- [ ] All existing 287 tests still pass
- [ ] Invalid requests return 400 BAD_REQUEST (not 500)
- [ ] Error responses include proper error codes
- [ ] Null values in critical paths are handled gracefully
- [ ] Domain exceptions are properly mapped to HTTP responses
- [ ] Manual tests for rollover flow still work

---

## 9. Related Files to Modify

```
src/main/java/com/multi/vidulum/cashflow/app/
├── CashFlowRestController.java          # Add @Valid annotations
├── CashFlowDto.java                     # Add validation annotations

src/main/java/com/multi/vidulum/cashflow/domain/
├── (new) InvalidRolloverPeriodException.java
├── (new) ForecastPeriodNotFoundException.java
├── (new) InvalidTargetPeriodException.java

src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/processing/
├── MonthRolledOverEventHandler.java     # Add null checks
├── HistoricalCashChangeImportedEventHandler.java
├── ExpectedCashChangeAppendedEventHandler.java
├── PaidCashChangeAppendedEventHandler.java
├── MonthAttestedEventHandler.java

src/main/java/com/multi/vidulum/common/error/
├── ErrorCode.java                       # Add new error codes
├── ErrorHttpHandler.java                # Add new exception handlers
```

---

*Report generated: 2026-02-08*
*Next review: Before next release*
