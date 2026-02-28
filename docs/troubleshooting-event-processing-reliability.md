# Troubleshooting: Event Processing Reliability

## Problem Description

Events (`ExpectedCashChangeAppendedEvent`) from Recurring Rules are intermittently not being processed by `CashFlowForecastProcessor`. The result is that:
- `cash-flow-document` (MongoDB) correctly contains new cash changes
- `cash-flow-forecast-statement` (MongoDB) has 0 transactions from recurring rules
- Events ARE being emitted (logs show `CashFlowEventEmitter: Event emitted: ExpectedCashChangeAppendedEvent`)
- But `ForecastProcessor` is not receiving/processing them consistently

### Symptoms

- Forecast dashboard doesn't show transactions created by recurring rules
- Sometimes events are processed, sometimes not (intermittent behavior)
- No error logs visible (exceptions are silently swallowed)

---

## Root Cause Analysis

### 1. Fire-and-Forget Event Emission (HIGH RISK)

**Location:** `AppendExpectedCashChangeCommandHandler.java` lines 69-74

```java
cashFlowEventEmitter.emit(
    CashFlowUnifiedEvent.builder()
        .metadata(Map.of("event", CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName()))
        .content(JsonContent.asPrettyJson(event))
        .build()
);
```

**In `CashFlowEventEmitter.java`:**
```java
public void emit(CashFlowUnifiedEvent event) {
    log.info("Event emitted: [{}]", event);
    cashFlowUnifiedEventKafkaTemplate.send("cash_flow", event);  // No .get()!
}
```

**Problem:** The Kafka `send()` call returns a `SendResult` future but it's never awaited or checked. If Kafka is slow, unavailable, or network issues occur, the event may silently fail to be delivered.

**Alternative available:** There's an `emitWithKey()` method that properly waits for delivery, but it's not being used.

---

### 2. No Error Handler on Kafka Listener Container (HIGH RISK)

**Location:** `KafkaTopicConfig.java` lines 254-259

```java
public ConcurrentKafkaListenerContainerFactory<String, CashFlowUnifiedEvent> cashFlowUnifiedEventContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, CashFlowUnifiedEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(cashFlowUnifiedEventConsumerFactory());
    return factory;
}
```

**Problem:** No error handler is configured. If an exception occurs during event processing, the listener container may:
- Silently swallow the exception
- Go into an error state without retrying
- Leave the event unprocessed without acknowledgment
- Depending on consumer group settings, cause rebalancing or deadlock

**Missing configuration:**
- No `setCommonErrorHandler()`
- No retry policy
- No dead-letter topic configuration

---

### 3. No Exception Handling in Event Listener (HIGH RISK)

**Location:** `CashFlowEventListener.java` lines 22-26

```java
public void on(CashFlowUnifiedEvent event) {
    log.debug("CashFlowUnifiedEvent captured: [{}]", event);
    CashFlowEvent cashFlowEvent = map(event);  // Can throw IllegalStateException
    cashFlowForecastProcessor.process(cashFlowEvent);  // Can throw any exception
}
```

**Problems:**
- No try-catch block
- Exceptions in `map()` method will crash the listener
- Exceptions in `process()` will crash the listener
- No logging of exceptions
- Event will not be acknowledged if exception occurs
- Kafka consumer may enter error state

---

### 4. Race Condition - Forecast Doesn't Exist (MEDIUM RISK)

**Location:** `ExpectedCashChangeAppendedEventHandler.java` lines 22-23

```java
CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
    .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));
```

**Problem:** If the event arrives before the `CashFlowCreatedEvent` is fully processed, the statement won't exist and the event will be rejected with an exception.

---

### 5. Missing Month in Forecast (MEDIUM RISK)

**Location:** `ExpectedCashChangeAppendedEventHandler.java` line 28

```java
YearMonth yearMonth = YearMonth.from(event.dueDate());
statement.getForecasts().compute(yearMonth, (yearMonth1, cashFlowMonthlyForecast) -> {
    // ...
});
```

**Problem:** The `compute()` method receives `null` for `cashFlowMonthlyForecast` if the month doesn't exist in the forecast map. This causes `NullPointerException` when trying to access category methods.

---

### 6. Category Not Found in Forecast (MEDIUM RISK)

**Location:** `ExpectedCashChangeAppendedEventHandler.java` lines 33-34, 46-47

```java
uncategorizedCashCategory = cashFlowMonthlyForecast.findCategoryOutflowsByCategoryName(event.categoryName())
    .orElseThrow(() -> new IllegalStateException(
        String.format("Cannot find cash-category with name %s in OUTFLOWS", event.categoryName())));
```

**Problem:** If the category doesn't exist in the forecast structure, an `IllegalStateException` is thrown, causing the event to be rejected.

---

### 7. Dual Processing with Potential Inconsistency (MEDIUM RISK)

**Location:** `CashFlowForecastProcessor.java` lines 41-44

```java
public void process(CashFlowEvent cashFlowEvent) {
    oldProcessing(cashFlowEvent);    // Saves to MongoDB event log
    processEvent(cashFlowEvent);     // Updates forecasts
}
```

**Problems:**
- Two separate database operations
- If `oldProcessing()` succeeds but `processEvent()` fails, event log and forecast are out of sync
- No transaction across both operations
- Named "oldProcessing" suggests legacy code that may not be needed

---

### 8. No Idempotency Tracking (MEDIUM RISK)

**Location:** `CashFlowEventHandler.java` lines 19-22

```java
default void updateSyncMetadata(CashFlowForecastStatement statement, CashFlowEvent event) {
    statement.setLastModification(event.occurredAt());
    statement.setLastMessageChecksum(getChecksum(event));
}
```

**Problem:** The checksum is updated but there's no logic to detect and skip duplicate processing. If an event is delivered twice (Kafka at-least-once guarantee), the forecast will be updated twice, leading to incorrect data.

---

## Event Processing Flow

```
RecurringRuleService.generateExpectedCashChanges()
  └─> CashFlowHttpClient.createExpectedCashChange()
        └─> POST /cash-flow/expected-cash-change
              └─> CashFlowController.appendExpectedCashChange()
                    └─> AppendExpectedCashChangeCommandHandler.handle()
                          └─> CashFlow.apply(event)
                          └─> domainCashFlowRepository.save(cashFlow)
                          └─> cashFlowEventEmitter.emit(event)  ← FIRE-AND-FORGET
                                └─> Kafka topic "cash_flow"
                                      └─> CashFlowEventListener.on()  ← NO ERROR HANDLING
                                            └─> CashFlowForecastProcessor.process()
                                                  └─> ExpectedCashChangeAppendedEventHandler.handle()
                                                        └─> Update forecast statement
                                                        └─> statementRepository.save()
```

---

## Solution

### 1. Use `emitWithKey()` Instead of `emit()`

**File:** `AppendExpectedCashChangeCommandHandler.java`

Change:
```java
cashFlowEventEmitter.emit(
    CashFlowUnifiedEvent.builder()
        .metadata(Map.of("event", CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName()))
        .content(JsonContent.asPrettyJson(event))
        .build()
);
```

To:
```java
cashFlowEventEmitter.emitWithKey(
    command.cashFlowId(),
    CashFlowUnifiedEvent.builder()
        .metadata(Map.of("event", CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName()))
        .content(JsonContent.asPrettyJson(event))
        .build()
);
```

**Benefits:**
- Guarantees event delivery (blocks until Kafka acknowledges)
- Preserves event ordering for the same cashFlowId
- Throws exception if delivery fails (can be handled/retried)

---

### 2. Add Error Handler to Kafka Listener Container

**File:** `KafkaTopicConfig.java`

Change:
```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, CashFlowUnifiedEvent> cashFlowUnifiedEventContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, CashFlowUnifiedEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(cashFlowUnifiedEventConsumerFactory());
    return factory;
}
```

To:
```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, CashFlowUnifiedEvent> cashFlowUnifiedEventContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, CashFlowUnifiedEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(cashFlowUnifiedEventConsumerFactory());

    // Add error handler with retry (3 retries, 1 second apart)
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(
        new FixedBackOff(1000L, 3));
    factory.setCommonErrorHandler(errorHandler);

    return factory;
}
```

**Benefits:**
- Automatic retry on transient failures
- Prevents silent exception swallowing
- Configurable backoff strategy

---

### 3. Add Exception Handling in `CashFlowEventListener.on()`

**File:** `CashFlowEventListener.java`

Change:
```java
public void on(CashFlowUnifiedEvent event) {
    log.debug("CashFlowUnifiedEvent captured: [{}]", event);
    CashFlowEvent cashFlowEvent = map(event);
    cashFlowForecastProcessor.process(cashFlowEvent);
}
```

To:
```java
public void on(CashFlowUnifiedEvent event) {
    String eventType = (String) event.getMetadata().get("event");
    try {
        log.info("Processing CashFlowUnifiedEvent: [{}]", eventType);
        CashFlowEvent cashFlowEvent = map(event);
        cashFlowForecastProcessor.process(cashFlowEvent);
        log.info("Successfully processed event: [{}]", eventType);
    } catch (Exception e) {
        log.error("Failed to process CashFlowUnifiedEvent: [{}], error: {}",
            eventType, e.getMessage(), e);
        throw e; // Re-throw to trigger retry via error handler
    }
}
```

**Benefits:**
- Visible error logging
- Exception details captured for debugging
- Re-throw allows retry via error handler

---

### 4. Handle Missing Month in `ExpectedCashChangeAppendedEventHandler`

**File:** `ExpectedCashChangeAppendedEventHandler.java`

Add before the `compute()` call:
```java
YearMonth yearMonth = YearMonth.from(event.dueDate());

// Ensure month exists before processing
if (!statement.getForecasts().containsKey(yearMonth)) {
    log.warn("Month {} not found in forecast for cashFlowId {}, creating dynamically",
        yearMonth, event.cashFlowId());
    // Add months until we reach the target month
    while (!statement.getForecasts().containsKey(yearMonth)) {
        statement.addNextForecastAtTheTop();
    }
}

statement.getForecasts().compute(yearMonth, (ym, cashFlowMonthlyForecast) -> {
    // existing logic...
});
```

**Benefits:**
- Handles events for future months gracefully
- Creates missing months on-demand
- No more NullPointerException

---

### 5. Graceful Handling for Missing Category

**File:** `ExpectedCashChangeAppendedEventHandler.java`

Change:
```java
uncategorizedCashCategory = cashFlowMonthlyForecast.findCategoryOutflowsByCategoryName(event.categoryName())
    .orElseThrow(() -> new IllegalStateException(
        String.format("Cannot find cash-category with name %s in OUTFLOWS", event.categoryName())));
```

To:
```java
uncategorizedCashCategory = cashFlowMonthlyForecast
    .findCategoryOutflowsByCategoryName(event.categoryName())
    .orElseGet(() -> {
        log.warn("Category [{}] not found in OUTFLOWS for cashFlowId {}, using Uncategorized",
            event.categoryName(), event.cashFlowId());
        return cashFlowMonthlyForecast
            .findCategoryOutflowsByCategoryName(new CategoryName("Uncategorized"))
            .orElseThrow(() -> new IllegalStateException("Uncategorized category not found"));
    });
```

**Benefits:**
- Graceful fallback to Uncategorized
- Warning logged for investigation
- Event still processed instead of rejected

---

### 6. Add Idempotency Check (Optional Enhancement)

**File:** `ExpectedCashChangeAppendedEventHandler.java`

Add at the beginning of `handle()`:
```java
@Override
public void handle(CashFlowEvent.ExpectedCashChangeAppendedEvent event) {
    CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
            .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

    // Idempotency check - skip if already processed
    Checksum eventChecksum = getChecksum(event);
    if (eventChecksum.equals(statement.getLastMessageChecksum())) {
        log.info("Event already processed (checksum match), skipping: {}", event.cashChangeId());
        return;
    }

    // ... rest of processing
}
```

**Benefits:**
- Prevents duplicate processing
- Safe for Kafka at-least-once delivery
- No data corruption from retries

---

## Verification Steps

After implementing fixes:

1. **Check Kafka connectivity:**
   ```bash
   docker logs vidulum-kafka 2>&1 | grep -i error
   ```

2. **Monitor event emission:**
   ```bash
   # In application logs, look for:
   grep "Event emitted with key" app.log
   ```

3. **Monitor event processing:**
   ```bash
   # In application logs, look for:
   grep "Successfully processed event" app.log
   grep "Failed to process" app.log
   ```

4. **Verify forecast updates:**
   ```bash
   # In MongoDB
   db.getCollection('cash-flow-forecast-statement').find({cashFlowId: "CF..."})
   ```

---

## Related Files

### Core Implementation
- `src/main/java/com/multi/vidulum/cashflow/app/commands/append/AppendExpectedCashChangeCommandHandler.java`
- `src/main/java/com/multi/vidulum/cashflow/domain/CashFlowEventEmitter.java`
- `src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/CashFlowEventListener.java`
- `src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/processing/CashFlowForecastProcessor.java`
- `src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/processing/ExpectedCashChangeAppendedEventHandler.java`

### Configuration
- `src/main/java/com/multi/vidulum/quotation/KafkaTopicConfig.java`

### Related Documentation
- `docs/troubleshooting-sourceruleid-race-condition.md` - Related race condition issue
- `docs/features-backlog/TODO-kafka-dead-letter-queue.md` - Future DLQ implementation

---

*Created: 2026-02-28*
*Status: Analysis complete, fixes pending implementation*
