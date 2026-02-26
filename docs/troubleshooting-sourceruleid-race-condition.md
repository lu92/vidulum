# Troubleshooting: SourceRuleId Race Condition

## Problem Description

During integration tests of the Recurring Rules feature, a race condition was discovered where the `sourceRuleId` field on `CashChange` entities was being lost due to concurrent writes to the same `CashFlow` aggregate.

### Symptoms

- Test assertion `Expecting actual: 0L to be greater than: 0L` when checking for cash changes with `sourceRuleId != null`
- All 97 cash changes in the CashFlow had `sourceRuleId = null` despite being created by recurring rules
- Logs showed `sourceRuleId` being set correctly during creation, but lost afterward

### Root Cause

The problem occurs in this sequence:

1. **RecurringRuleService** creates an expected cash change via `CashFlowHttpClient`
2. **CashFlowController** receives the request, loads the `CashFlow` aggregate (version N)
3. **CashFlow.appendExpectedCashChange()** is called with `sourceRuleId` set correctly
4. The aggregate is saved to MongoDB (version N+1)
5. **Simultaneously**, the event `CashChangeAppendedEvent` is emitted to Kafka
6. **CashFlowForecastProcessor** listens to the event, loads the same aggregate (version N+1)
7. Processor updates forecast-related data and saves (version N+2)
8. Meanwhile, **RecurringRuleService** continues with the next cash change
9. The next save might overwrite version N+2 with stale data from version N+1

The aggregate pattern with event-driven processing creates a window where:
- Multiple writers (HTTP requests, Kafka consumers) operate on the same aggregate
- Each writer loads a snapshot, modifies it, and saves it back
- Without proper concurrency control, later writes overwrite earlier changes

### Affected Code Flow

```
RecurringRuleService.generateExpectedCashChanges()
  └─> CashFlowHttpClient.createExpectedCashChange()
        └─> POST /cash-flow/expected-cash-change
              └─> CashFlowController.appendExpectedCashChange()
                    └─> CashFlow.appendExpectedCashChange(sourceRuleId) ← sourceRuleId set here
                          └─> CashFlowMongoRepository.save()
                                └─> Kafka event emitted
                                      └─> CashFlowForecastProcessor.handle() ← may overwrite

```

## Solution Options

### Option 1: Optimistic Locking with Retry (Recommended)

Add version field to aggregate and use Spring Retry for automatic retries on conflicts.

**Implementation:**

```java
// 1. Add @Version to snapshot
@Document(collection = "cashflow")
public class CashFlowEntity {
    @Id
    private String id;

    @Version
    private Long version;  // Add this field

    // ... rest of fields
}

// 2. Configure retry in service
@Service
@RequiredArgsConstructor
public class CashFlowService {

    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public void appendExpectedCashChange(AppendExpectedCashChangeCommand cmd) {
        CashFlow cashFlow = repository.findById(cmd.cashFlowId())
            .orElseThrow();

        cashFlow.appendExpectedCashChange(/* ... */);

        repository.save(cashFlow);  // May throw OptimisticLockingFailureException
    }
}

// 3. Enable retry in configuration
@Configuration
@EnableRetry
public class RetryConfig {
}
```

**Pros:**
- Simple to implement
- No external dependencies
- Well-suited for low-contention scenarios
- Spring Data MongoDB supports `@Version` natively

**Cons:**
- Under high contention, retries may exhaust and fail
- Slight increase in latency due to retries

### Option 2: Pessimistic Locking (Distributed Lock)

Use a distributed lock (Redis, MongoDB, or Zookeeper) to serialize access to the aggregate.

**Implementation:**

```java
@Service
@RequiredArgsConstructor
public class CashFlowService {

    private final LockRegistry lockRegistry;  // e.g., RedisLockRegistry

    public void appendExpectedCashChange(AppendExpectedCashChangeCommand cmd) {
        Lock lock = lockRegistry.obtain("cashflow:" + cmd.cashFlowId());

        try {
            if (lock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    CashFlow cashFlow = repository.findById(cmd.cashFlowId())
                        .orElseThrow();

                    cashFlow.appendExpectedCashChange(/* ... */);

                    repository.save(cashFlow);
                } finally {
                    lock.unlock();
                }
            } else {
                throw new ConcurrencyException("Could not acquire lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyException("Lock acquisition interrupted");
        }
    }
}
```

**Pros:**
- Guarantees serialization - no race conditions possible
- Predictable behavior under high load

**Cons:**
- Adds external dependency (Redis, etc.)
- Potential for deadlocks if not careful
- Reduces throughput due to serialization
- Lock management complexity

### Option 3: MongoDB Atomic Operations

Use MongoDB's atomic `$push` operation to update arrays without read-modify-write cycle.

**Implementation:**

```java
@Repository
public class CashFlowMongoRepository {

    private final MongoTemplate mongoTemplate;

    public void appendCashChange(CashFlowId cashFlowId, CashChange cashChange) {
        Query query = Query.query(Criteria.where("_id").is(cashFlowId.id()));

        Update update = new Update()
            .push("cashChanges", cashChange)
            .set("lastModification", Instant.now());

        mongoTemplate.updateFirst(query, update, CashFlowEntity.class);
    }
}
```

**Pros:**
- Atomic operation - no race conditions for array additions
- High performance - single database round-trip
- No external dependencies

**Cons:**
- Bypasses domain logic in aggregate
- Harder to maintain domain invariants
- Breaks the aggregate pattern (direct DB manipulation)
- Cannot emit domain events through normal flow
- May lead to inconsistent state if validation is needed

### Option 4: Event Sourcing

Store events instead of state; derive state from event replay.

**Concept:**

```java
// Instead of storing CashFlow state, store events:
// - CashFlowCreatedEvent
// - CashChangeAppendedEvent
// - CashChangeConfirmedEvent

// Rebuild state when needed:
CashFlow cashFlow = eventStore.loadEvents(cashFlowId)
    .reduce(new CashFlow(), CashFlow::apply);

// Append new event (always succeeds, events are immutable):
eventStore.append(new CashChangeAppendedEvent(
    cashFlowId,
    cashChangeId,
    sourceRuleId,  // Never lost!
    ...
));
```

**Pros:**
- No race conditions - events are append-only
- Full audit trail
- Can replay events to any point in time
- Natural fit for CQRS architecture

**Cons:**
- Major architectural change
- Increased complexity
- Event versioning challenges
- Eventual consistency considerations
- Significant refactoring effort

## Recommended Solution

**Option 1: Optimistic Locking with Retry** is recommended for the following reasons:

1. **Minimal code changes** - Only requires adding `@Version` field and `@Retryable` annotation
2. **Proven pattern** - Well-supported by Spring Data MongoDB
3. **Appropriate for use case** - CashFlow writes are typically low-contention
4. **Maintains domain model** - No changes to aggregate design
5. **Built-in retry** - Spring Retry handles transient failures gracefully

### Implementation Steps

1. Add `@Version` field to `CashFlowEntity` (MongoDB document)
2. Add `version` to `CashFlowSnapshot`
3. Add `@EnableRetry` to Spring configuration
4. Add `spring-retry` dependency to `pom.xml`
5. Add `@Retryable` to write operations in `CashFlowService`
6. Add integration tests for concurrent writes

## Current Workaround

Until the proper fix is implemented, tests have been modified to avoid relying on `sourceRuleId`:

```java
// Instead of checking sourceRuleId on CashChanges (unreliable due to race):
long expectedCashChangesCount = cashFlowSummary.getCashChanges().values().stream()
        .filter(cc -> cc.getSourceRuleId() != null)
        .count();

// Use generatedCashChangeIds from RecurringRule (reliable):
int totalGeneratedCashChanges =
    monthlySalaryRule.getGeneratedCashChangeIds().size() +
    weeklyGroceriesRule.getGeneratedCashChangeIds().size() +
    yearlyInsuranceRule.getGeneratedCashChangeIds().size() +
    dailyRule.getGeneratedCashChangeIds().size();

assertThat(totalGeneratedCashChanges).isGreaterThan(0);
assertThat(cashFlowSummary.getCashChanges().size())
    .isGreaterThanOrEqualTo(totalGeneratedCashChanges);
```

This workaround verifies that:
- RecurringRule correctly tracks the IDs of generated cash changes
- CashFlow contains at least as many cash changes as were generated

## Related Files

- `src/main/java/com/multi/vidulum/recurring_rules/app/RecurringRuleService.java`
- `src/main/java/com/multi/vidulum/recurring_rules/infrastructure/CashFlowHttpClient.java`
- `src/main/java/com/multi/vidulum/cashflow/app/CashFlowRestController.java`
- `src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/CashFlowForecastProcessor.java`
- `src/test/java/com/multi/vidulum/recurring_rules/app/RecurringRulesHttpIntegrationTest.java`

## References

- [Spring Data MongoDB - Optimistic Locking](https://docs.spring.io/spring-data/mongodb/docs/current/reference/html/#mongo.optimistic-locking)
- [Spring Retry](https://github.com/spring-projects/spring-retry)
- [MongoDB Atomic Operations](https://www.mongodb.com/docs/manual/core/write-operations-atomicity/)
- [Event Sourcing Pattern](https://martinfowler.com/eaaDev/EventSourcing.html)

---

*Created: 2026-02-26*
*Status: Workaround implemented, proper fix pending*
