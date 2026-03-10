# VID-149: Recurring Rules - Execution History & Event-Driven Tracking

## Overview

This document analyzes the current event flow between RecurringRule and CashFlow aggregates, identifies gaps in historical data tracking, and proposes implementation for execution history feature.

## Current Architecture

### Event Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              CURRENT EVENT ARCHITECTURE                                      │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────┐                              ┌─────────────────────┐
│   RecurringRule     │         HTTP calls           │      CashFlow       │
│   (MongoDB)         │ ───────────────────────────► │      (MongoDB)      │
│                     │                              │                      │
│  emit(RuleEvent)    │                              │  emit(CashFlowEvent) │
│       │             │                              │         │            │
│       ▼             │                              │         ▼            │
│   eventConsumer     │                              │   KafkaTemplate      │
│   (NULL - unused!)  │                              │         │            │
│                     │                              │         ▼            │
└─────────────────────┘                              │   Kafka "cash_flow"  │
                                                     │         │            │
                                                     └─────────┼────────────┘
                                                               │
                                                               ▼
                                              ┌─────────────────────────────────┐
                                              │   CashFlowEventListener         │
                                              │   (Kafka Consumer)              │
                                              │         │                       │
                                              │         ▼                       │
                                              │   CashFlowForecastProcessor     │
                                              │   (updates forecast statements) │
                                              └─────────────────────────────────┘
```

**Key observation**: RecurringRule events are emitted locally but `eventConsumer` is never registered - events are lost!

---

## User Journey with Events

### Step 1: Create RecurringRule

```
POST /api/v1/recurring-rules

RecurringRule                         CashFlow
┌─────────────────────┐               ┌─────────────────────┐
│ create()            │               │                     │
│   emit(RuleCreated) │──X──────────► │                     │  ← NOT sent to Kafka!
│   (local only)      │               │                     │     eventConsumer is NULL
└─────────────────────┘               └─────────────────────┘
```

### Step 2: Generate Expected CashChanges

```
generateExpectedCashChanges() - HTTP calls to CashFlow API

RecurringRule                         CashFlow
┌─────────────────────┐    HTTP       ┌─────────────────────┐
│ for each date:      │──────────────►│ append(...)         │
│   HTTP POST         │               │   emit(Expected     │
│   /cash-change      │               │   CashChangeAppended│ ──► Kafka "cash_flow"
│                     │               │   Event)            │
│ recordGenerated     │               │   sourceRuleId: ✓   │
│ CashChanges()       │               └─────────────────────┘
│   emit(Expected     │
│   CashChangesGen-   │──X──────────► NOT sent to Kafka!
│   erated) (local)   │
└─────────────────────┘

Result in CashFlow:
┌─────────────────────────────────────────────────────────────┐
│ CC001: Rent | 2026-03-01 | 2000 PLN | PENDING | rule=RR001 │
│ CC002: Rent | 2026-04-01 | 2000 PLN | PENDING | rule=RR001 │
│ CC003: Rent | 2026-05-01 | 2000 PLN | PENDING | rule=RR001 │
│ ...                                                         │
└─────────────────────────────────────────────────────────────┘
```

### Step 3: User Confirms Payment

```
POST /cash-flow/{cfId}/cash-change/{ccId}/confirm

CashFlow                              Kafka
┌─────────────────────┐               ┌─────────────────────┐
│ confirmCashChange() │               │ cash_flow topic     │
│   status: CONFIRMED │──────────────►│ CashChangeConfirmed │
│   endDate: 2026-03-5│               │ Event               │
│                     │               │ {                   │
│                     │               │   cashFlowId,       │
│                     │               │   cashChangeId,     │
│                     │               │   endDate           │
│                     │               │   ❌ NO sourceRuleId│  ← PROBLEM!
│                     │               │ }                   │
└─────────────────────┘               └─────────────────────┘
                                             │
                                             ▼
                             ┌─────────────────────────────────┐
                             │ CashFlowForecastProcessor       │
                             │   → Updates forecast statement  │
                             │   → RecurringRule NOT notified! │  ← GAP!
                             └─────────────────────────────────┘
```

### Step 4: Pause Rule

```
POST /api/v1/recurring-rules/{id}/pause

RecurringRule                         CashFlow
┌─────────────────────┐    HTTP       ┌─────────────────────┐
│ 1. clearGenerated   │──────────────►│ batchDelete()       │
│    CashChanges()    │               │   emit(BatchDeleted)│──► Kafka
│                     │               └─────────────────────┘
│ 2. pause()          │
│    emit(RulePaused) │──X──────────► NOT sent to Kafka!
└─────────────────────┘
```

### Step 5: Add Amount Change (Rent Increase)

```
POST /api/v1/recurring-rules/{id}/amount-changes

RecurringRule                         CashFlow
┌─────────────────────┐               ┌─────────────────────┐
│ addAmountChange()   │               │                     │
│   emit(AmountChange │──X──────────► │ NOT notified!       │
│   Added) (local)    │               │                     │
│                     │    HTTP       │                     │
│ clearGenerated...() │──────────────►│ batchDelete()       │──► Kafka
│ generateExpected...()│──────────────►│ append() x N       │──► Kafka
└─────────────────────┘               └─────────────────────┘
```

---

## Event Emission Summary

### CashFlow Events (Kafka)

| Event | Sent to Kafka | Has sourceRuleId |
|-------|--------------|------------------|
| `ExpectedCashChangeAppendedEvent` | ✅ YES | ✅ YES |
| `CashChangeConfirmedEvent` | ✅ YES | ❌ **NO** |
| `CashChangeRejectedEvent` | ✅ YES | ❌ NO |
| `ExpectedCashChangeDeletedEvent` | ✅ YES | ✅ YES |
| `ExpectedCashChangesBatchDeletedEvent` | ✅ YES | ✅ YES |
| `CashChangesBatchUpdatedEvent` | ✅ YES | ✅ YES |

### RecurringRule Events (Local only - NOT on Kafka)

| Event | Sent to Kafka | Notes |
|-------|--------------|-------|
| `RuleCreated` | ❌ NO | eventConsumer is null |
| `RuleUpdated` | ❌ NO | eventConsumer is null |
| `RulePaused` | ❌ NO | eventConsumer is null |
| `RuleResumed` | ❌ NO | eventConsumer is null |
| `RuleCompleted` | ❌ NO | eventConsumer is null |
| `RuleDeleted` | ❌ NO | eventConsumer is null |
| `AmountChangeAdded` | ❌ NO | eventConsumer is null |
| `AmountChangeRemoved` | ❌ NO | eventConsumer is null |
| `RuleExecuted` | ❌ NO | eventConsumer is null |
| `ExpectedCashChangesGenerated` | ❌ NO | eventConsumer is null |
| `ExpectedCashChangesCleared` | ❌ NO | eventConsumer is null |

---

## Existing Data Structures (Ready but Unused)

### RuleExecution Record

```java
// Already exists in: RecurringRule.java
public record RuleExecution(
    LocalDate executionDate,      // Due date for the payment
    Instant executedAt,           // When execution occurred
    ExecutionStatus status,       // SUCCESS, FAILED, SKIPPED
    CashChangeId generatedCashChangeId,  // Created CC (null if failed)
    Money executedAmount,         // Amount used
    String failureReason          // Error message (null if success)
)
```

### ExecutionStatus Enum

```java
public enum ExecutionStatus {
    SUCCESS,   // CashChange created/confirmed
    FAILED,    // HTTP error or validation failure
    SKIPPED    // Date excluded or inactive month
}
```

### RecurringRule has executions list

```java
// RecurringRule.java line 54
private List<RuleExecution> executions;

// Method exists but is NEVER called:
public void recordExecution(RuleExecution execution, Clock clock) {
    this.executions.add(execution);
    // ...
    emit(new RecurringRuleEvent.RuleExecuted(ruleId, execution, clock.instant()));
}
```

---

## Data We Can Collect

### Currently Available (No Code Changes)

| Data | Source | How to Get |
|------|--------|------------|
| Generated CashChange IDs | `RecurringRule.generatedCashChangeIds` | GET rule |
| PENDING vs CONFIRMED count | HTTP query to CashFlow | On-demand |
| AmountChanges list | `RecurringRule.amountChanges` | GET rule |

### After Adding `sourceRuleId` to `CashChangeConfirmedEvent`

| Data | Source | Collection Method |
|------|--------|-------------------|
| When payment confirmed | `CashChangeConfirmedEvent.endDate` | Kafka listener |
| Which CC was confirmed | `CashChangeConfirmedEvent.cashChangeId` | Kafka listener |
| Execution history | `RecurringRule.executions` | Save in listener |

### Full History (If RecurringRule Events Were on Kafka)

| Data | Event |
|------|-------|
| Rule creation time | `RuleCreated` |
| Amount changes | `AmountChangeAdded` |
| Pause events | `RulePaused` |
| Resume events | `RuleResumed` |
| Generation batches | `ExpectedCashChangesGenerated` |

---

## Example Response with Full History

```json
{
  "ruleId": "RR00000001",
  "name": "Czynsz",
  "baseAmount": {"amount": 2000.00, "currency": "PLN"},
  "status": "ACTIVE",

  "executionHistory": [
    {
      "dueDate": "2026-01-01",
      "cashChangeId": "CC00001",
      "amount": {"amount": 2000.00, "currency": "PLN"},
      "status": "CONFIRMED",
      "generatedAt": "2025-12-15T10:00:00Z",
      "confirmedAt": "2026-01-03T14:30:00Z"
    },
    {
      "dueDate": "2026-02-01",
      "cashChangeId": "CC00002",
      "amount": {"amount": 2000.00, "currency": "PLN"},
      "status": "CONFIRMED",
      "generatedAt": "2025-12-15T10:00:00Z",
      "confirmedAt": "2026-02-02T09:15:00Z"
    },
    {
      "dueDate": "2026-03-01",
      "cashChangeId": "CC00003",
      "amount": {"amount": 2200.00, "currency": "PLN"},
      "status": "PENDING",
      "generatedAt": "2026-02-20T08:00:00Z",
      "confirmedAt": null
    }
  ],

  "amountChanges": [
    {
      "id": "AC00001",
      "amount": {"amount": 2200.00, "currency": "PLN"},
      "type": "PERMANENT",
      "reason": "Podwyżka czynszu od marca",
      "addedAt": "2026-02-20T08:00:00Z"
    }
  ],

  "lifecycleEvents": [
    {"type": "CREATED", "at": "2025-12-15T10:00:00Z"},
    {"type": "PAUSED", "at": "2026-01-15T12:00:00Z", "reason": "Wakacje"},
    {"type": "RESUMED", "at": "2026-01-20T09:00:00Z"},
    {"type": "AMOUNT_CHANGED", "at": "2026-02-20T08:00:00Z", "newAmount": 2200.00}
  ],

  "statistics": {
    "totalGenerated": 12,
    "totalConfirmed": 2,
    "totalPending": 10,
    "totalPaidAmount": {"amount": 4000.00, "currency": "PLN"},
    "averagePaymentDelay": "2.5 days"
  }
}
```

---

## Implementation Plan

### Phase 1: Add sourceRuleId to CashChangeConfirmedEvent (Required)

**Files to modify:**
1. `CashFlowEvent.java` - Add `sourceRuleId` field to `CashChangeConfirmedEvent`
2. `ConfirmCashChangeCommandHandler.java` - Pass sourceRuleId when creating event

**Estimated time:** 30 minutes

### Phase 2: Create RecurringRuleEventListener (Core Feature)

**New files:**
1. `RecurringRuleEventListener.java` - Kafka consumer for cash_flow topic
2. Filter for events with non-null `sourceRuleId`
3. Update `RecurringRule.executions` when CashChange is confirmed

**Estimated time:** 2 hours

### Phase 3: Add executionHistory to Response DTO

**Files to modify:**
1. `RecurringRuleResponse.java` - Add `executionHistory` field
2. Create `ExecutionHistoryItem` DTO
3. Map from `RuleExecution` domain object

**Estimated time:** 1 hour

### Phase 4: Add Statistics (Optional Enhancement)

**New features:**
- `totalConfirmed`, `totalPending` counts
- `totalPaidAmount` sum
- `averagePaymentDelay` calculation

**Estimated time:** 2 hours

---

## Architecture After Implementation

```
┌─────────────────────┐                              ┌─────────────────────┐
│   RecurringRule     │         HTTP calls           │      CashFlow       │
│   (MongoDB)         │ ───────────────────────────► │      (MongoDB)      │
│                     │                              │                      │
│                     │                              │  emit(CashFlowEvent) │
│                     │                              │         │            │
│                     │                              │         ▼            │
│                     │                              │   Kafka "cash_flow"  │
│                     │                              │         │            │
│                     │◄─────────────────────────────┼─────────┘            │
│                     │  CashChangeConfirmedEvent    │                      │
│                     │  (with sourceRuleId)         │                      │
│                     │                              │                      │
│ recordExecution()   │                              │                      │
│ executions.add(...) │                              │                      │
└─────────────────────┘                              └─────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ RecurringRuleEventListener (NEW)                                            │
│   @KafkaListener(topics = "cash_flow")                                      │
│   - Filter: event.sourceRuleId != null                                      │
│   - Handle CashChangeConfirmedEvent → rule.recordExecution(SUCCESS)         │
│   - Handle CashChangeRejectedEvent → rule.recordExecution(FAILED)           │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Priority

**MEDIUM** - Nice to have for auditing and user transparency. Does not affect core functionality.

## Related Issues

- VID-145: Pause/Resume Fix (completed)
- VID-148: Atomicity/Saga Pattern (related - both deal with Rule↔CashFlow sync)

## Acceptance Criteria

### Phase 1 (Minimal):
- [ ] `CashChangeConfirmedEvent` contains `sourceRuleId`
- [ ] Events emitted correctly with rule reference

### Phase 2 (Core):
- [ ] `RecurringRuleEventListener` processes confirmation events
- [ ] `RecurringRule.executions` populated on payment confirmation
- [ ] `executionHistory` visible in GET rule response

### Phase 3 (Enhanced):
- [ ] Statistics calculated and returned
- [ ] Lifecycle events tracked (optional)

---

## Notes

- `AmountChange` already has basic history (list in rule) but lacks `createdAt` and `effectiveDate` fields
- `RuleExecution` structure exists and is well-designed - just needs to be used
- Event-driven approach is preferred over polling for real-time updates
