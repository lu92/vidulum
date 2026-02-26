# Event-Driven Architecture (EDA) Proposal for Recurring Rules

## Overview

This document proposes replacing HTTP-based communication between `recurring-rules` and `cashflow` bounded contexts with Event-Driven Architecture using Kafka.

## Current State (HTTP-based)

```
┌─────────────────────┐    HTTP POST     ┌─────────────────────┐
│   RecurringRules    │ ───────────────► │     CashFlow        │
│                     │ ◄─────────────── │                     │
│   (synchronous)     │    Response      │                     │
└─────────────────────┘                  └─────────────────────┘
```

**Problems with HTTP approach:**
- Tight coupling between services
- Synchronous blocking calls
- Complex retry/circuit breaker configuration
- Service availability dependency
- Harder to test in isolation

## Proposed State (Event-Driven)

```
┌─────────────────────┐                  ┌─────────────────────┐
│   RecurringRules    │                  │     CashFlow        │
│                     │                  │                     │
│  ┌───────────────┐  │                  │  ┌───────────────┐  │
│  │ RuleExecutor  │  │                  │  │ EventListener │  │
│  └───────┬───────┘  │                  │  └───────┬───────┘  │
│          │          │                  │          │          │
│          ▼          │                  │          ▼          │
│  ┌───────────────┐  │                  │  ┌───────────────┐  │
│  │EventPublisher │  │                  │  │CommandHandler │  │
│  └───────┬───────┘  │                  │  └───────┬───────┘  │
└──────────┼──────────┘                  └──────────┼──────────┘
           │                                        │
           ▼                                        ▼
    ┌──────────────────────────────────────────────────────┐
    │                      KAFKA                           │
    │  ┌────────────────────────────────────────────────┐  │
    │  │  recurring_rules.commands (Intent Events)      │  │
    │  └────────────────────────────────────────────────┘  │
    │  ┌────────────────────────────────────────────────┐  │
    │  │  cash_flow (Success Events)                    │  │
    │  └────────────────────────────────────────────────┘  │
    │  ┌────────────────────────────────────────────────┐  │
    │  │  failures (Failure Events / DLQ)               │  │
    │  └────────────────────────────────────────────────┘  │
    └──────────────────────────────────────────────────────┘
```

---

## Correlation ID Pattern

### Purpose

Correlation ID is a UUID that links all events in a single flow, enabling:
- End-to-end traceability
- Debugging and monitoring
- Matching intent with result (success/failure)

### Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        CORRELATION ID FLOW                                   │
│                                                                             │
│  correlationId: "550e8400-e29b-41d4-a716-446655440000"                     │
│                                                                             │
│  ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐     │
│  │   INTENT        │      │   PROCESSING    │      │   RESULT        │     │
│  │                 │      │                 │      │                 │     │
│  │ CreateCashChange│ ──►  │ CashFlow        │ ──►  │ Success OR      │     │
│  │ Intent          │      │ processes       │      │ Failure Event   │     │
│  │                 │      │ command         │      │                 │     │
│  │ correlationId:  │      │                 │      │ correlationId:  │     │
│  │ 550e8400...     │      │                 │      │ 550e8400...     │     │
│  └─────────────────┘      └─────────────────┘      └─────────────────┘     │
│                                                                             │
│  ◄────────────────────── SAME correlationId ──────────────────────────►    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Correlation vs Causation ID

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Event 1 (Intent)                                                          │
│  ├── eventId: "evt-001"           (unique ID of THIS event)               │
│  ├── correlationId: "corr-123"    (ID of the entire flow)                 │
│  └── causationId: null            (no parent event)                       │
│                                                                            │
│  Event 2 (Success)                                                         │
│  ├── eventId: "evt-002"           (unique ID of THIS event)               │
│  ├── correlationId: "corr-123"    (SAME as Event 1 - same flow)          │
│  └── causationId: "evt-001"       (caused by Event 1)                     │
│                                                                            │
│  Event 3 (Cascading - Forecast Update)                                     │
│  ├── eventId: "evt-003"           (unique ID of THIS event)               │
│  ├── correlationId: "corr-123"    (SAME - still same flow)               │
│  └── causationId: "evt-002"       (caused by Event 2)                     │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## Event Definitions

### Event Envelope (Common Wrapper)

```java
public record EventEnvelope<T>(
    String eventId,           // Unique ID of this event
    String correlationId,     // Flow tracking ID
    String causationId,       // ID of event that caused this one
    String eventType,         // e.g., "CreateCashChangeIntent"
    Instant timestamp,        // When event was created
    String source,            // e.g., "recurring-rules-service"
    int version,              // Schema version for evolution
    T payload                 // Actual event data
) {}
```

### Intent Events (recurring-rules → cashflow)

**Topic:** `recurring_rules.commands`

#### CreateCashChangeIntent

```java
public record CreateCashChangeIntent(
    String ruleId,            // RR10000001
    String cashFlowId,        // CF10000001
    String categoryId,        // CAT-uuid
    String name,              // "Netflix Subscription"
    String description,       // Optional description
    Money amount,             // { amount: 15.99, currency: "PLN" }
    CashChangeType type,      // INFLOW or OUTFLOW
    LocalDate dueDate,        // 2026-03-10
    String idempotencyKey     // "RR10000001-2026-03-10" (prevents duplicates)
) {}
```

#### UpdateCashChangeIntent

```java
public record UpdateCashChangeIntent(
    String ruleId,
    String cashFlowId,
    String cashChangeId,      // CC-uuid (existing CashChange to update)
    String categoryId,
    String name,
    String description,
    Money amount,
    LocalDate dueDate,
    String idempotencyKey
) {}
```

#### DeleteCashChangeIntent

```java
public record DeleteCashChangeIntent(
    String ruleId,
    String cashFlowId,
    String cashChangeId,
    String reason,            // "Rule deleted" or "Rule paused"
    String idempotencyKey
) {}
```

### Success Events (cashflow → recurring-rules)

**Topic:** `cash_flow`

#### CashChangeCreatedFromRuleEvent

```java
public record CashChangeCreatedFromRuleEvent(
    String cashChangeId,      // Newly created CC-uuid
    String cashFlowId,
    String ruleId,            // Which rule triggered this
    String categoryId,
    String name,
    Money amount,
    CashChangeType type,
    LocalDate dueDate,
    CashChangeStatus status,  // PLANNED
    Instant createdAt
) {}
```

#### CashChangeUpdatedFromRuleEvent

```java
public record CashChangeUpdatedFromRuleEvent(
    String cashChangeId,
    String cashFlowId,
    String ruleId,
    // ... updated fields
    Instant updatedAt
) {}
```

#### CashChangeDeletedFromRuleEvent

```java
public record CashChangeDeletedFromRuleEvent(
    String cashChangeId,
    String cashFlowId,
    String ruleId,
    String reason,
    Instant deletedAt
) {}
```

### Failure Events

**Topic:** `failures`

#### Sealed Interface for Type Safety

```java
public sealed interface RuleExecutionFailure {
    String correlationId();
    String ruleId();
    String cashFlowId();
    Instant failedAt();
    boolean retryable();
}

// NON-RETRYABLE FAILURES (Business validation errors)

public record CategoryNotFoundFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String categoryId,
    String message,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
}

public record CategoryArchivedFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String categoryId,
    String categoryName,
    Instant archivedAt,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
}

public record CashFlowNotFoundFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
}

public record CashFlowClosedFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    Instant closedAt,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
}

public record MonthNotActiveFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    YearMonth targetMonth,
    YearMonth activeMonth,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
}

public record DuplicateCashChangeFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    String existingCashChangeId,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
}

// RETRYABLE FAILURES (Technical errors)

public record DatabaseTimeoutFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String operation,
    Duration timeout,
    int attemptNumber,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return true; }
}

public record KafkaPublishFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String targetTopic,
    String errorMessage,
    int attemptNumber,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return true; }
}

public record TransientServiceFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String serviceName,
    String errorCode,
    String errorMessage,
    int attemptNumber,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return true; }
}
```

---

## Timeline Examples

### Happy Path - Successful CashChange Creation

```
Timeline: Successful CashChange Creation
═══════════════════════════════════════════════════════════════════════════

T+0ms   [RecurringRules] SchedulerTrigger fires for rule RR10000001
        │
        ├── Generate correlationId: "corr-abc-123"
        ├── Generate eventId: "evt-001"
        │
T+5ms   [RecurringRules] Publish to topic: recurring_rules.commands
        │
        │   EventEnvelope {
        │     eventId: "evt-001",
        │     correlationId: "corr-abc-123",
        │     causationId: null,
        │     eventType: "CreateCashChangeIntent",
        │     timestamp: "2026-03-01T00:00:05Z",
        │     source: "recurring-rules-service",
        │     payload: {
        │       ruleId: "RR10000001",
        │       cashFlowId: "CF10000001",
        │       categoryId: "CAT-netflix",
        │       name: "Netflix Subscription",
        │       amount: { amount: 15.99, currency: "PLN" },
        │       type: "OUTFLOW",
        │       dueDate: "2026-03-10",
        │       idempotencyKey: "RR10000001-2026-03-10"
        │     }
        │   }
        │
T+50ms  [CashFlow] EventListener receives CreateCashChangeIntent
        │
        ├── Extract correlationId from envelope
        ├── Set MDC.put("correlationId", "corr-abc-123")
        ├── Log: "Processing CreateCashChangeIntent [correlationId=corr-abc-123]"
        │
T+55ms  [CashFlow] Validate request:
        │   ✓ CashFlow CF10000001 exists
        │   ✓ CashFlow is OPEN
        │   ✓ Category CAT-netflix exists and is active
        │   ✓ Month 2026-03 is active
        │   ✓ No duplicate (idempotencyKey not found)
        │
T+100ms [CashFlow] Create CashChange in MongoDB
        │
        │   CashChange {
        │     id: "CC-xyz-789",
        │     cashFlowId: "CF10000001",
        │     categoryId: "CAT-netflix",
        │     name: "Netflix Subscription",
        │     amount: 15.99 PLN,
        │     type: OUTFLOW,
        │     status: PLANNED,
        │     dueDate: 2026-03-10,
        │     originRuleId: "RR10000001"  // Link to rule
        │   }
        │
T+120ms [CashFlow] Publish success event to topic: cash_flow
        │
        │   EventEnvelope {
        │     eventId: "evt-002",
        │     correlationId: "corr-abc-123",    // SAME as intent!
        │     causationId: "evt-001",           // Caused by intent
        │     eventType: "CashChangeCreatedFromRuleEvent",
        │     timestamp: "2026-03-01T00:00:120Z",
        │     source: "cashflow-service",
        │     payload: {
        │       cashChangeId: "CC-xyz-789",
        │       cashFlowId: "CF10000001",
        │       ruleId: "RR10000001",
        │       ...
        │     }
        │   }
        │
T+150ms [CashFlowForecastProcessor] Receives CashChangeCreatedFromRuleEvent
        │
        ├── Recalculate forecast for CF10000001
        ├── Update MonthlyForecast for 2026-03
        │
T+200ms [RecurringRules] FailureListener checks topic: failures
        │
        └── No failure for correlationId "corr-abc-123"
            (Success path - no action needed)
        │
T+250ms [RecurringRules] SuccessListener receives CashChangeCreatedFromRuleEvent
        │
        ├── Match correlationId "corr-abc-123" with pending execution
        ├── Update RuleExecutionHistory:
        │     {
        │       ruleId: "RR10000001",
        │       correlationId: "corr-abc-123",
        │       status: "SUCCESS",
        │       cashChangeId: "CC-xyz-789",
        │       executedAt: "2026-03-01T00:00:00Z",
        │       completedAt: "2026-03-01T00:00:250Z",
        │       latencyMs: 250
        │     }
        │
        └── Rule execution completed successfully!

═══════════════════════════════════════════════════════════════════════════
```

### Validation Failure - Category Not Found

```
Timeline: Category Not Found (Non-Retryable Failure)
═══════════════════════════════════════════════════════════════════════════

T+0ms   [RecurringRules] SchedulerTrigger fires for rule RR10000002
        │
        ├── Generate correlationId: "corr-def-456"
        │
T+5ms   [RecurringRules] Publish CreateCashChangeIntent
        │
        │   payload: {
        │     ruleId: "RR10000002",
        │     categoryId: "CAT-deleted-category",  // This category was deleted!
        │     ...
        │   }
        │
T+50ms  [CashFlow] EventListener receives CreateCashChangeIntent
        │
T+55ms  [CashFlow] Validate request:
        │   ✓ CashFlow exists
        │   ✗ Category CAT-deleted-category NOT FOUND  ← VALIDATION FAILURE
        │
T+60ms  [CashFlow] Publish failure event to topic: failures
        │
        │   EventEnvelope {
        │     eventId: "evt-fail-001",
        │     correlationId: "corr-def-456",
        │     causationId: "evt-intent-002",
        │     eventType: "CategoryNotFoundFailure",
        │     payload: {
        │       ruleId: "RR10000002",
        │       cashFlowId: "CF10000001",
        │       categoryId: "CAT-deleted-category",
        │       message: "Category does not exist or has been deleted",
        │       retryable: false,      // NON-RETRYABLE!
        │       failedAt: "2026-03-01T00:00:060Z"
        │     }
        │   }
        │
T+100ms [RecurringRules] FailureListener receives CategoryNotFoundFailure
        │
        ├── Match correlationId "corr-def-456"
        ├── Check retryable: false
        │
        ├── AUTO-PAUSE the rule:
        │     UPDATE recurring_rules
        │     SET status = 'PAUSED',
        │         pauseReason = 'Category not found: CAT-deleted-category',
        │         pausedAt = NOW()
        │     WHERE ruleId = 'RR10000002'
        │
        ├── Create notification for user (optional)
        │
        └── Update RuleExecutionHistory:
              {
                ruleId: "RR10000002",
                correlationId: "corr-def-456",
                status: "FAILED",
                failureType: "CategoryNotFoundFailure",
                failureMessage: "Category does not exist",
                retryable: false,
                autoAction: "RULE_PAUSED"
              }

═══════════════════════════════════════════════════════════════════════════
```

### Technical Failure - Database Timeout (Retryable)

```
Timeline: Database Timeout (Retryable Failure)
═══════════════════════════════════════════════════════════════════════════

T+0ms   [RecurringRules] SchedulerTrigger fires for rule RR10000003
        │
        ├── Generate correlationId: "corr-ghi-789"
        │
T+5ms   [RecurringRules] Publish CreateCashChangeIntent
        │
T+50ms  [CashFlow] EventListener receives CreateCashChangeIntent
        │
T+55ms  [CashFlow] Validate request: ✓ All validations pass
        │
T+60ms  [CashFlow] Try to save CashChange to MongoDB...
        │
T+5060ms [CashFlow] MongoDB TIMEOUT after 5000ms!
        │
        ├── Log error: "Database timeout saving CashChange"
        │
T+5070ms [CashFlow] Publish failure event to topic: failures
        │
        │   EventEnvelope {
        │     eventId: "evt-fail-002",
        │     correlationId: "corr-ghi-789",
        │     eventType: "DatabaseTimeoutFailure",
        │     payload: {
        │       ruleId: "RR10000003",
        │       operation: "INSERT_CASH_CHANGE",
        │       timeout: "PT5S",
        │       attemptNumber: 1,
        │       retryable: true,       // RETRYABLE!
        │       failedAt: "2026-03-01T00:00:5070Z"
        │     }
        │   }
        │
T+5100ms [RecurringRules] FailureListener receives DatabaseTimeoutFailure
         │
         ├── Match correlationId "corr-ghi-789"
         ├── Check retryable: true
         ├── Check attemptNumber: 1 (max: 3)
         │
         ├── Schedule retry with exponential backoff:
         │     retryAt = NOW() + (2^attemptNumber * 1000ms)
         │     retryAt = NOW() + 2000ms
         │
         └── Update RuleExecutionHistory:
               {
                 correlationId: "corr-ghi-789",
                 status: "PENDING_RETRY",
                 attemptNumber: 1,
                 nextRetryAt: "2026-03-01T00:00:7100Z"
               }

--- RETRY ATTEMPT 2 ---

T+7100ms [RecurringRules] RetryScheduler triggers retry
         │
         ├── Generate new eventId: "evt-intent-003"
         ├── Keep SAME correlationId: "corr-ghi-789"
         │
T+7105ms [RecurringRules] Publish CreateCashChangeIntent (retry)
         │
         │   EventEnvelope {
         │     eventId: "evt-intent-003",
         │     correlationId: "corr-ghi-789",  // SAME!
         │     attemptNumber: 2,
         │     ...
         │   }
         │
T+7150ms [CashFlow] EventListener receives retry attempt
         │
T+7200ms [CashFlow] MongoDB responds successfully this time
         │
T+7220ms [CashFlow] Publish CashChangeCreatedFromRuleEvent
         │
T+7250ms [RecurringRules] SuccessListener receives success
         │
         └── Update RuleExecutionHistory:
               {
                 correlationId: "corr-ghi-789",
                 status: "SUCCESS",
                 attemptNumber: 2,
                 totalRetries: 1,
                 finalLatencyMs: 7250
               }

═══════════════════════════════════════════════════════════════════════════
```

---

## Execution History Model

```java
@Document(collection = "rule_execution_history")
public class RuleExecutionHistory {

    @Id
    private String id;

    private String ruleId;
    private String correlationId;      // Links to all related events
    private String cashFlowId;

    private ExecutionStatus status;    // PENDING, IN_PROGRESS, SUCCESS, FAILED, PENDING_RETRY

    // Timing
    private Instant triggeredAt;       // When scheduler fired
    private Instant completedAt;       // When final result received
    private Duration totalDuration;    // End-to-end latency

    // Result tracking
    private String resultCashChangeId; // If SUCCESS - created CashChange ID
    private FailureDetails failure;    // If FAILED - failure details

    // Retry tracking
    private int attemptNumber;         // Current attempt (1, 2, 3...)
    private int maxAttempts;           // Configured max (e.g., 3)
    private Instant nextRetryAt;       // If PENDING_RETRY
    private List<AttemptRecord> attempts; // History of all attempts

    // Audit
    private String autoAction;         // NONE, RULE_PAUSED, NOTIFICATION_SENT

    public enum ExecutionStatus {
        PENDING,        // Intent sent, waiting for response
        IN_PROGRESS,    // Processing by CashFlow
        SUCCESS,        // CashChange created
        FAILED,         // Non-retryable failure
        PENDING_RETRY   // Retryable failure, scheduled for retry
    }

    public record AttemptRecord(
        int attemptNumber,
        Instant attemptedAt,
        String eventId,
        AttemptResult result,
        String failureType,
        String failureMessage,
        Duration latency
    ) {}

    public enum AttemptResult {
        SUCCESS,
        RETRYABLE_FAILURE,
        NON_RETRYABLE_FAILURE
    }

    public record FailureDetails(
        String failureType,     // CategoryNotFoundFailure, DatabaseTimeoutFailure, etc.
        String message,
        boolean retryable,
        Instant failedAt
    ) {}
}
```

---

## Monitoring and Debugging

### MDC (Mapped Diagnostic Context) Logging

```java
// In CashFlow EventListener
@KafkaListener(topics = "recurring_rules.commands")
public void handleIntent(EventEnvelope<CreateCashChangeIntent> envelope) {
    try (var ignored = MDC.putCloseable("correlationId", envelope.correlationId());
         var ignored2 = MDC.putCloseable("eventId", envelope.eventId());
         var ignored3 = MDC.putCloseable("ruleId", envelope.payload().ruleId())) {

        log.info("Processing CreateCashChangeIntent");
        // All subsequent logs will include correlationId!

        // Process...

        log.info("CashChange created successfully");
    }
}
```

### Log Output Example

```
2026-03-01 00:00:050 INFO  [correlationId=corr-abc-123] [ruleId=RR10000001] Processing CreateCashChangeIntent
2026-03-01 00:00:055 DEBUG [correlationId=corr-abc-123] [ruleId=RR10000001] Validating CashFlow CF10000001
2026-03-01 00:00:060 DEBUG [correlationId=corr-abc-123] [ruleId=RR10000001] Validating category CAT-netflix
2026-03-01 00:00:100 INFO  [correlationId=corr-abc-123] [ruleId=RR10000001] CashChange CC-xyz-789 created
2026-03-01 00:00:120 INFO  [correlationId=corr-abc-123] [ruleId=RR10000001] Published CashChangeCreatedFromRuleEvent
```

### Grafana Dashboard Queries

```promql
# Execution success rate by rule
sum(rate(rule_execution_total{status="SUCCESS"}[5m])) by (rule_id)
/
sum(rate(rule_execution_total[5m])) by (rule_id)

# Average execution latency
histogram_quantile(0.95,
  sum(rate(rule_execution_duration_seconds_bucket[5m])) by (le, rule_id)
)

# Retry rate
sum(rate(rule_execution_retry_total[5m])) by (failure_type)

# Failed rules requiring attention
count(rule_execution_history{status="FAILED", retryable="false"}) by (rule_id)
```

---

## Comparison: HTTP vs EDA

| Aspect | HTTP (Current) | EDA (Proposed) |
|--------|----------------|----------------|
| **Coupling** | Tight - direct dependency | Loose - via events |
| **Availability** | Requires both services online | Async, tolerates downtime |
| **Testing** | Requires mocking HTTP client | Easier with embedded Kafka |
| **Retry Logic** | Complex (Resilience4j) | Built into Kafka consumer |
| **Traceability** | Request/Response logs | Full event audit trail |
| **Scalability** | Limited by HTTP connections | Horizontal with partitions |
| **Complexity** | Simpler to implement | More infrastructure |
| **Latency** | Lower (sync) | Higher (async) |
| **Consistency** | Immediate | Eventually consistent |

---

## Summary

### Benefits of EDA Approach

1. **Loose Coupling** - Services communicate via events, no direct dependencies
2. **Resilience** - Kafka handles retries, failures are persisted
3. **Auditability** - Full event history with correlation IDs
4. **Testability** - Easier to test with embedded Kafka
5. **Scalability** - Horizontal scaling via Kafka partitions
6. **Failure Handling** - Clear distinction between retryable and non-retryable failures

### Implementation Approach

1. **Phase 1**: Category validation stays HTTP (synchronous, needed before rule creation)
2. **Phase 2**: CashChange creation moves to EDA (asynchronous execution)
3. **Phase 3**: Add monitoring, dashboards, alerting

---

## Open Questions

### 1. Retry Strategy Details
- What should be the exponential backoff configuration? (base delay, max delay, max attempts)
- Should retries be time-limited (e.g., give up after 24 hours)?
- How to handle "infinite" retryable failures (e.g., MongoDB down for extended period)?

### 2. Integration Testing
- Should we use Embedded Kafka or Testcontainers Kafka?
- How to test the full flow (intent → success/failure) in integration tests?
- What about testing retry scenarios?

### 3. Monitoring and Alerting
- What metrics should trigger alerts?
- Should we alert on:
  - High failure rate?
  - Long retry queues?
  - Rules auto-paused?
- Integration with existing monitoring (Prometheus/Grafana)?

### 4. Migration Path
- How to migrate existing rules from HTTP to EDA?
- Feature flag for gradual rollout?
- Fallback to HTTP if EDA fails?

### 5. Category Validation
- Should category validation also move to EDA?
- Or keep it synchronous (HTTP) for immediate feedback during rule creation?
- What about real-time validation in UI?

### 6. Idempotency
- How long to keep idempotency keys in cache?
- What if same rule executes twice in same month due to timing issues?
- Should idempotency be per-month or per-execution?

### 7. Dead Letter Queue (DLQ)
- What should happen to events in DLQ after max retries?
- Manual intervention process?
- Automatic archival after X days?

### 8. Event Schema Evolution
- How to handle schema changes (new fields, removed fields)?
- Avro/Protobuf vs JSON for serialization?
- Schema registry integration?

---

## Next Steps

1. Review and answer open questions
2. Create detailed implementation plan
3. Update existing design documents if needed
4. Implement Phase 1 (keep HTTP for validation)
5. Implement Phase 2 (EDA for CashChange creation)
6. Add monitoring and alerting (Phase 3)
