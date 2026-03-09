# VID-148: Recurring Rules - Atomicity & Saga Pattern

## Problem Statement

Current Pause/Resume operations in Recurring Rules lack atomicity between RecurringRule (MongoDB) and CashFlow (separate aggregate/HTTP call). This can lead to data inconsistencies in failure scenarios.

## Current Implementation Flow

### clearGeneratedCashChanges():
```java
1. batchDeleteExpectedCashChanges() → HTTP call to CashFlow (deletes CashChanges)
2. rule.clearGeneratedCashChanges()  → updates IDs list in rule object
3. ruleRepository.save(rule)         → saves rule to MongoDB
```

### generateExpectedCashChanges():
```java
1. Calculate occurrences
2. For each occurrence:
   a. HTTP call to create CashChange in CashFlow
   b. rule.addGeneratedCashChangeId()
3. ruleRepository.save(rule)
```

## Failure Scenarios

### Scenario 1: Failure after HTTP delete, before rule save
- HTTP call succeeds (CashChanges deleted in CashFlow)
- Application crashes before `ruleRepository.save(rule)`
- **Result**: CashChanges deleted, but `generatedCashChangeIds` still contains old IDs
- **Impact**: Next operations may try to delete non-existent CashChanges (handled gracefully) or rule state is inconsistent

### Scenario 2: Failure during generate, after some CashChanges created
- 5 out of 10 CashChanges created successfully
- Application crashes
- **Result**: 5 CashChanges exist in CashFlow but are not tracked in rule
- **Impact**: "Orphaned" CashChanges that won't be cleaned up on pause/delete

### Scenario 3: Cyclic Pause/Resume accumulating orphans
- Each cycle has small probability of partial failure
- Over time, orphaned CashChanges accumulate
- **Impact**: Forecast pollution, incorrect financial projections

## Root Cause

Two separate data stores (RecurringRule MongoDB collection, CashFlow aggregate) without distributed transaction support. No 2PC (two-phase commit) mechanism.

## Solution Options

### Option A: Saga Pattern with Compensation

Add compensating actions for rollback:

```java
public void clearGeneratedCashChanges(RecurringRule rule, String authToken) {
    List<CashChangeId> toDelete = rule.getGeneratedCashChangeIds();

    try {
        // Step 1: Delete from CashFlow
        cashFlowHttpClient.batchDeleteExpectedCashChanges(...);

        // Step 2: Update rule
        rule.clearGeneratedCashChanges(toDelete, clock);
        ruleRepository.save(rule);

    } catch (Exception e) {
        // Compensation: Re-create deleted CashChanges (complex!)
        // Or: Mark rule as "needs reconciliation"
        throw e;
    }
}
```

**Pros**: Full consistency guarantee
**Cons**: Complex compensation logic, re-creating CashChanges is non-trivial

### Option B: Idempotency + Scheduled Reconciliation

Add a scheduled job that verifies consistency:

```java
@Scheduled(cron = "0 0 4 * * *") // 04:00 UTC daily
public void reconcileRulesWithCashChanges() {
    for (RecurringRule rule : ruleRepository.findAll()) {
        List<CashChangeId> tracked = rule.getGeneratedCashChangeIds();
        List<CashChangeId> actual = cashFlowService.findBySourceRuleId(rule.getRuleId());

        // Find orphans (in CashFlow but not tracked)
        Set<CashChangeId> orphans = new HashSet<>(actual);
        orphans.removeAll(tracked);

        // Clean up orphans
        if (!orphans.isEmpty()) {
            cashFlowService.batchDelete(orphans);
            log.warn("Cleaned {} orphaned CashChanges for rule {}", orphans.size(), rule.getRuleId());
        }

        // Find phantoms (tracked but not in CashFlow)
        Set<CashChangeId> phantoms = new HashSet<>(tracked);
        phantoms.removeAll(actual);

        if (!phantoms.isEmpty()) {
            rule.removePhantomIds(phantoms);
            ruleRepository.save(rule);
            log.warn("Removed {} phantom IDs from rule {}", phantoms.size(), rule.getRuleId());
        }
    }
}
```

**Pros**: Simple, handles edge cases gracefully, self-healing
**Cons**: Temporary inconsistency window (up to 24h), requires system token for HTTP calls

### Option C: Change Operation Order (Intent-First)

Save intent in rule BEFORE making HTTP calls:

```java
public void clearGeneratedCashChanges(RecurringRule rule, String authToken) {
    List<CashChangeId> toDelete = rule.getGeneratedCashChangeIds();

    // Step 1: Mark intent in rule (idempotent marker)
    rule.markPendingClear(toDelete, clock);
    ruleRepository.save(rule);

    // Step 2: Execute HTTP calls (can be retried)
    cashFlowHttpClient.batchDeleteExpectedCashChanges(...);

    // Step 3: Confirm completion
    rule.confirmClear(clock);
    ruleRepository.save(rule);
}
```

On application restart, check for rules with `pendingClear` status and retry.

**Pros**: Crash-safe, retryable
**Cons**: More complex state machine, two saves per operation

### Option D: Event Sourcing / Outbox Pattern

Store operations as events in outbox table, process asynchronously:

```java
// Instead of direct HTTP call:
outboxRepository.save(new DeleteCashChangesEvent(ruleId, cashChangeIds));

// Separate processor:
@Scheduled(fixedDelay = 1000)
public void processOutbox() {
    for (OutboxEvent event : outboxRepository.findPending()) {
        try {
            process(event);
            event.markCompleted();
        } catch (Exception e) {
            event.incrementRetry();
        }
        outboxRepository.save(event);
    }
}
```

**Pros**: Guaranteed delivery, full audit trail
**Cons**: Eventually consistent, significant architectural change

## Recommendation

**Short-term (VID-148a)**: Implement **Option B** (Reconciliation Scheduler)
- Low risk, self-healing
- Can run alongside existing code
- Handles all edge cases eventually

**Medium-term (VID-148b)**: Implement **Option C** (Intent-First)
- Better consistency guarantees
- Requires state machine in RecurringRule

## Priority

**Medium** - Current implementation works in happy path. Failures are rare and impact is limited (orphaned PENDING CashChanges can be manually cleaned).

## Related Issues

- VID-147: System Auth Token for Schedulers (needed for reconciliation job)
- VID-145: Pause/Resume Fix (current implementation)

## Acceptance Criteria

### VID-148a (Reconciliation):
- [ ] Scheduled job runs daily at 04:00 UTC
- [ ] Detects and cleans orphaned CashChanges
- [ ] Detects and removes phantom IDs from rules
- [ ] Logs all reconciliation actions
- [ ] Metrics for monitoring (orphan count, phantom count)

### VID-148b (Intent-First):
- [ ] RecurringRule has `pendingOperation` state
- [ ] Operations are crash-safe and retryable
- [ ] Startup recovery for interrupted operations
- [ ] Unit tests for all failure scenarios
