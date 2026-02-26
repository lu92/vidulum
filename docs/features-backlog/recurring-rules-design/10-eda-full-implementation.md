# Event-Driven Architecture - Full Implementation Design

## Overview

This document describes a complete Event-Driven Architecture (EDA) implementation for communication between `recurring-rules` and `cashflow` bounded contexts, replacing HTTP-based communication.

**Key Features:**
- Asynchronous event-based communication via Kafka
- Partitioning by `cashFlowId` for ordering guarantees
- Dead Letter Queue (DLQ) for failed events
- Completion tracking with batch execution support
- WebSocket notifications for real-time UI updates
- Full traceability via correlation IDs and metadata

---

## 1. Kafka Topics Architecture

### 1.1 Topic Design

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           KAFKA TOPICS                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  recurring_rules.commands                                            │   │
│  │  ─────────────────────────────────────────────────────────────────── │   │
│  │  Purpose: Intent events from RecurringRules → CashFlow               │   │
│  │  Partitions: 12 (partitioned by cashFlowId)                          │   │
│  │  Retention: 7 days                                                   │   │
│  │  Key: cashFlowId                                                     │   │
│  │  Consumer Group: cashflow-rule-executor                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  recurring_rules.results                                             │   │
│  │  ─────────────────────────────────────────────────────────────────── │   │
│  │  Purpose: Success/Failure results from CashFlow → RecurringRules     │   │
│  │  Partitions: 12 (partitioned by cashFlowId)                          │   │
│  │  Retention: 7 days                                                   │   │
│  │  Key: cashFlowId                                                     │   │
│  │  Consumer Group: recurring-rules-result-handler                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  recurring_rules.dlq                                                 │   │
│  │  ─────────────────────────────────────────────────────────────────── │   │
│  │  Purpose: Dead Letter Queue for unprocessable events                 │   │
│  │  Partitions: 3                                                       │   │
│  │  Retention: 30 days (longer for debugging)                           │   │
│  │  Consumer: Manual/Admin processing                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  recurring_rules.notifications                                       │   │
│  │  ─────────────────────────────────────────────────────────────────── │   │
│  │  Purpose: UI notifications via WebSocket Gateway                     │   │
│  │  Partitions: 6                                                       │   │
│  │  Retention: 1 day                                                    │   │
│  │  Key: userId                                                         │   │
│  │  Consumer Group: websocket-gateway                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Topic Configuration

```yaml
# kafka-topics.yml
kafka:
  topics:
    recurring-rules-commands:
      name: recurring_rules.commands
      partitions: 12
      replication-factor: 3
      configs:
        retention.ms: 604800000          # 7 days
        cleanup.policy: delete
        min.insync.replicas: 2

    recurring-rules-results:
      name: recurring_rules.results
      partitions: 12
      replication-factor: 3
      configs:
        retention.ms: 604800000          # 7 days
        cleanup.policy: delete
        min.insync.replicas: 2

    recurring-rules-dlq:
      name: recurring_rules.dlq
      partitions: 3
      replication-factor: 3
      configs:
        retention.ms: 2592000000         # 30 days
        cleanup.policy: delete

    recurring-rules-notifications:
      name: recurring_rules.notifications
      partitions: 6
      replication-factor: 3
      configs:
        retention.ms: 86400000           # 1 day
        cleanup.policy: delete
```

### 1.3 Partitioning Strategy

```java
/**
 * Events for the same CashFlow go to the same partition,
 * ensuring ordering per CashFlow.
 */
public class CashFlowPartitioner implements Partitioner {

    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        // Key is always cashFlowId
        String cashFlowId = (String) key;
        int numPartitions = cluster.partitionCountForTopic(topic);

        // Consistent hashing based on cashFlowId
        return Math.abs(cashFlowId.hashCode()) % numPartitions;
    }
}
```

---

## 2. Event Catalog

### 2.1 Event Envelope (Common Structure)

```java
/**
 * All events are wrapped in this envelope for consistent handling.
 */
public record EventEnvelope<T>(
    // === Identity ===
    String eventId,              // UUID - unique ID of this event
    String correlationId,        // UUID - links all events in a flow
    String causationId,          // eventId of the event that caused this one

    // === Routing ===
    String eventType,            // e.g., "CreateCashChangeIntent"
    String aggregateType,        // "RecurringRule" or "CashFlow"
    String aggregateId,          // ruleId or cashFlowId

    // === Metadata ===
    Instant timestamp,           // When event was created
    String source,               // e.g., "recurring-rules-service"
    int schemaVersion,           // For schema evolution (start at 1)

    // === Batch Context ===
    String batchId,              // Groups events from same scheduler run
    int batchSequence,           // Position in batch (1-based)
    int batchSize,               // Total events in batch

    // === Testing ===
    Map<String, String> testMetadata,  // For test assertions

    // === Payload ===
    T payload
) {
    public static <T> EventEnvelope<T> create(
            String eventType,
            String aggregateType,
            String aggregateId,
            T payload,
            String correlationId,
            String causationId
    ) {
        return new EventEnvelope<>(
            UUID.randomUUID().toString(),
            correlationId != null ? correlationId : UUID.randomUUID().toString(),
            causationId,
            eventType,
            aggregateType,
            aggregateId,
            Instant.now(),
            "recurring-rules-service",
            1,
            null, 0, 0,
            null,
            payload
        );
    }

    public EventEnvelope<T> withBatchContext(String batchId, int sequence, int size) {
        return new EventEnvelope<>(
            eventId, correlationId, causationId,
            eventType, aggregateType, aggregateId,
            timestamp, source, schemaVersion,
            batchId, sequence, size,
            testMetadata,
            payload
        );
    }

    public EventEnvelope<T> withTestMetadata(Map<String, String> metadata) {
        return new EventEnvelope<>(
            eventId, correlationId, causationId,
            eventType, aggregateType, aggregateId,
            timestamp, source, schemaVersion,
            batchId, batchSequence, batchSize,
            metadata,
            payload
        );
    }
}
```

### 2.2 Intent Events (RecurringRules → CashFlow)

**Topic:** `recurring_rules.commands`

```java
// =====================================================
// CREATE CASH CHANGE INTENT
// =====================================================
public record CreateCashChangeIntent(
    // Rule reference
    String ruleId,                    // RR10000001
    String ruleName,                  // "Netflix Subscription"

    // Target CashFlow
    String cashFlowId,                // CF10000001
    String userId,                    // U10000001

    // CashChange details
    String categoryName,              // "Entertainment"
    String name,                      // "Netflix - March 2026"
    String description,               // "Monthly subscription"
    MoneyDto amount,                  // { amount: 15.99, currency: "PLN" }
    String type,                      // "OUTFLOW"
    LocalDate dueDate,                // 2026-03-10

    // Idempotency
    String idempotencyKey,            // "RR10000001-2026-03-10"

    // Execution context
    LocalDate scheduledDate,          // Original scheduled date
    int executionAttempt              // 1, 2, 3 for retries
) {}

// =====================================================
// UPDATE CASH CHANGE INTENT
// =====================================================
public record UpdateCashChangeIntent(
    String ruleId,
    String cashFlowId,
    String userId,

    // Existing CashChange to update
    String cashChangeId,              // CC10000100

    // Updated fields (null = no change)
    String categoryName,
    String name,
    String description,
    MoneyDto amount,
    LocalDate dueDate,

    String idempotencyKey,
    String reason                     // "Rule amount changed"
) {}

// =====================================================
// DELETE CASH CHANGE INTENT
// =====================================================
public record DeleteCashChangeIntent(
    String ruleId,
    String cashFlowId,
    String userId,

    String cashChangeId,              // CC10000100
    String idempotencyKey,
    String reason                     // "Rule deleted by user"
) {}

// =====================================================
// BATCH EXECUTION START
// =====================================================
public record BatchExecutionStarted(
    String batchId,                   // UUID
    String triggeredBy,               // "SCHEDULER" or "MANUAL"
    Instant scheduledFor,             // When batch was supposed to run
    int totalRules,                   // Number of rules in batch
    LocalDate executionDate           // Date being processed
) {}

// =====================================================
// BATCH EXECUTION COMPLETE
// =====================================================
public record BatchExecutionCompleted(
    String batchId,
    int successCount,
    int failureCount,
    int skippedCount,
    Duration totalDuration,
    Instant completedAt
) {}
```

### 2.3 Result Events (CashFlow → RecurringRules)

**Topic:** `recurring_rules.results`

```java
// =====================================================
// SUCCESS: CASH CHANGE CREATED
// =====================================================
public record CashChangeCreatedResult(
    // What was created
    String cashChangeId,              // CC10000100
    String cashFlowId,                // CF10000001

    // Source rule
    String ruleId,                    // RR10000001
    String idempotencyKey,            // For matching

    // Created CashChange details
    String name,
    String categoryName,
    MoneyDto amount,
    String type,
    String status,                    // "PLANNED"
    LocalDate dueDate,
    Instant createdAt,

    // Processing info
    Duration processingTime           // How long CashFlow took
) {}

// =====================================================
// SUCCESS: CASH CHANGE UPDATED
// =====================================================
public record CashChangeUpdatedResult(
    String cashChangeId,
    String cashFlowId,
    String ruleId,
    String idempotencyKey,

    Map<String, Object> updatedFields,  // What changed
    Instant updatedAt,
    Duration processingTime
) {}

// =====================================================
// SUCCESS: CASH CHANGE DELETED
// =====================================================
public record CashChangeDeletedResult(
    String cashChangeId,
    String cashFlowId,
    String ruleId,
    String idempotencyKey,

    String reason,
    Instant deletedAt,
    Duration processingTime
) {}

// =====================================================
// IDEMPOTENT: ALREADY EXISTS
// =====================================================
public record IdempotentResult(
    String idempotencyKey,
    String existingCashChangeId,      // Previously created CC
    String ruleId,
    String cashFlowId,

    Instant originalCreatedAt,        // When it was originally created
    String message                    // "CashChange already exists for this key"
) {}
```

### 2.4 Failure Events

**Topic:** `recurring_rules.results` (same topic, different eventType)

```java
/**
 * Sealed interface for type-safe failure handling.
 * Each failure type knows if it's retryable.
 */
public sealed interface RuleExecutionFailure {
    String correlationId();
    String ruleId();
    String cashFlowId();
    String idempotencyKey();
    Instant failedAt();
    boolean retryable();
    String errorCode();
    String errorMessage();
}

// =====================================================
// NON-RETRYABLE FAILURES (Business validation)
// =====================================================

public record CategoryNotFoundFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    String categoryName,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
    @Override public String errorCode() { return "RR004"; }
    @Override public String errorMessage() {
        return "Category '" + categoryName + "' not found in CashFlow";
    }
}

public record CategoryArchivedFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    String categoryName,
    Instant archivedAt,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
    @Override public String errorCode() { return "RR005"; }
    @Override public String errorMessage() {
        return "Category '" + categoryName + "' is archived since " + archivedAt;
    }
}

public record CategoryTypeMismatchFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    String categoryName,
    String expectedType,              // "OUTFLOW"
    String actualType,                // "INFLOW"
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
    @Override public String errorCode() { return "RR006"; }
    @Override public String errorMessage() {
        return "Category type mismatch: expected " + expectedType + ", got " + actualType;
    }
}

public record CashFlowNotFoundFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
    @Override public String errorCode() { return "RR003"; }
    @Override public String errorMessage() {
        return "CashFlow '" + cashFlowId + "' not found";
    }
}

public record CashFlowClosedFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    Instant closedAt,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
    @Override public String errorCode() { return "RR007"; }
    @Override public String errorMessage() {
        return "CashFlow was closed at " + closedAt;
    }
}

public record MonthNotActiveFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    YearMonth targetMonth,
    YearMonth activeMonth,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
    @Override public String errorCode() { return "RR008"; }
    @Override public String errorMessage() {
        return "Target month " + targetMonth + " is not active. Current: " + activeMonth;
    }
}

public record CurrencyMismatchFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    String ruleCurrency,
    String cashFlowCurrency,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return false; }
    @Override public String errorCode() { return "RR009"; }
    @Override public String errorMessage() {
        return "Currency mismatch: rule uses " + ruleCurrency +
               ", CashFlow uses " + cashFlowCurrency;
    }
}

// =====================================================
// RETRYABLE FAILURES (Technical)
// =====================================================

public record DatabaseTimeoutFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    String operation,                 // "INSERT_CASH_CHANGE"
    Duration timeout,
    int attemptNumber,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return true; }
    @Override public String errorCode() { return "RR501"; }
    @Override public String errorMessage() {
        return "Database timeout after " + timeout.toSeconds() + "s during " + operation;
    }
}

public record KafkaPublishFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    String targetTopic,
    String kafkaError,
    int attemptNumber,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return true; }
    @Override public String errorCode() { return "RR502"; }
    @Override public String errorMessage() {
        return "Failed to publish to " + targetTopic + ": " + kafkaError;
    }
}

public record TransientServiceFailure(
    String correlationId,
    String ruleId,
    String cashFlowId,
    String idempotencyKey,
    String serviceName,
    int httpStatus,                   // 503, 504, etc.
    String errorBody,
    int attemptNumber,
    Instant failedAt
) implements RuleExecutionFailure {
    @Override public boolean retryable() { return true; }
    @Override public String errorCode() { return "RR503"; }
    @Override public String errorMessage() {
        return serviceName + " returned " + httpStatus;
    }
}
```

### 2.5 Notification Events

**Topic:** `recurring_rules.notifications`

```java
// =====================================================
// UI NOTIFICATION EVENTS
// =====================================================

public record RuleExecutionNotification(
    String userId,                    // For routing to correct WebSocket
    String ruleId,
    String ruleName,
    String cashFlowId,

    NotificationType type,            // SUCCESS, FAILURE, PAUSED
    String title,
    String message,

    // For UI actions
    String actionUrl,                 // /cash-flows/CF.../transactions/CC...
    Map<String, Object> metadata,

    Instant timestamp
) {
    public enum NotificationType {
        SUCCESS,
        FAILURE,
        RULE_PAUSED,
        RULE_RESUMED,
        BATCH_COMPLETED
    }
}

public record BatchCompletedNotification(
    String userId,
    String batchId,
    LocalDate executionDate,

    int successCount,
    int failureCount,
    int skippedCount,

    List<FailureSummary> failures,    // Top 5 failures

    Instant timestamp
) {
    public record FailureSummary(
        String ruleId,
        String ruleName,
        String errorCode,
        String errorMessage
    ) {}
}
```

---

## 3. Dead Letter Queue (DLQ) Strategy

### 3.1 DLQ Event Structure

```java
public record DLQEvent(
    // Original event
    EventEnvelope<?> originalEvent,
    String originalTopic,
    int partition,
    long offset,

    // Failure info
    String failureReason,
    String exceptionClass,
    String exceptionMessage,
    String stackTrace,

    // Processing history
    int totalAttempts,
    List<ProcessingAttempt> attempts,

    // DLQ metadata
    Instant movedToDlqAt,
    String dlqReason,                 // "MAX_RETRIES_EXCEEDED", "POISON_PILL", etc.
    DLQCategory category
) {
    public record ProcessingAttempt(
        int attemptNumber,
        Instant attemptedAt,
        String failureType,
        String errorMessage
    ) {}

    public enum DLQCategory {
        DESERIALIZATION_ERROR,        // Cannot parse event
        VALIDATION_ERROR,             // Event structure invalid
        BUSINESS_ERROR,               // Non-retryable business failure
        MAX_RETRIES_EXCEEDED,         // Retryable failures exhausted
        UNKNOWN_ERROR                 // Unexpected exception
    }
}
```

### 3.2 DLQ Handler

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class DLQEventHandler {

    private final DLQRepository dlqRepository;
    private final AlertService alertService;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
        topics = "recurring_rules.dlq",
        groupId = "dlq-monitor"
    )
    public void handleDLQEvent(DLQEvent event) {
        // 1. Persist for analysis
        DLQDocument doc = dlqRepository.save(toDocument(event));

        // 2. Metrics
        Counter.builder("recurring_rules.dlq.events")
            .tag("category", event.category().name())
            .tag("original_topic", event.originalTopic())
            .register(meterRegistry)
            .increment();

        // 3. Alert for critical categories
        if (shouldAlert(event.category())) {
            alertService.sendAlert(
                AlertLevel.WARNING,
                "DLQ Event Received",
                String.format(
                    "Event %s moved to DLQ. Category: %s, Reason: %s",
                    event.originalEvent().eventId(),
                    event.category(),
                    event.dlqReason()
                )
            );
        }

        log.warn("DLQ Event processed: id={}, category={}, reason={}",
            event.originalEvent().eventId(),
            event.category(),
            event.dlqReason()
        );
    }

    private boolean shouldAlert(DLQEvent.DLQCategory category) {
        return category == DLQCategory.MAX_RETRIES_EXCEEDED ||
               category == DLQCategory.UNKNOWN_ERROR;
    }
}
```

### 3.3 DLQ Recovery Admin API

```java
@RestController
@RequestMapping("/api/v1/admin/dlq")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class DLQAdminController {

    private final DLQRepository dlqRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * List DLQ events with filtering
     */
    @GetMapping
    public Page<DLQEventSummary> listDLQEvents(
            @RequestParam(required = false) DLQCategory category,
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return dlqRepository.findByFilters(category, ruleId, fromDate, PageRequest.of(page, size));
    }

    /**
     * Replay single event back to original topic
     */
    @PostMapping("/{eventId}/replay")
    public ResponseEntity<ReplayResult> replayEvent(
            @PathVariable String eventId,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        DLQDocument doc = dlqRepository.findById(eventId)
            .orElseThrow(() -> new NotFoundException("DLQ event not found: " + eventId));

        if (!force && doc.getCategory() == DLQCategory.BUSINESS_ERROR) {
            return ResponseEntity.badRequest().body(
                new ReplayResult(false, "Cannot replay business errors without force=true")
            );
        }

        // Increment attempt counter and send back to original topic
        EventEnvelope<?> envelope = doc.getOriginalEvent();
        kafkaTemplate.send(doc.getOriginalTopic(), envelope.aggregateId(), envelope);

        doc.setReplayedAt(Instant.now());
        doc.setReplayCount(doc.getReplayCount() + 1);
        dlqRepository.save(doc);

        return ResponseEntity.ok(new ReplayResult(true, "Event replayed successfully"));
    }

    /**
     * Bulk replay events by category/date
     */
    @PostMapping("/replay-batch")
    public ResponseEntity<BatchReplayResult> replayBatch(
            @RequestBody BatchReplayRequest request
    ) {
        List<DLQDocument> events = dlqRepository.findByFilters(
            request.category(), request.ruleId(), request.fromDate(),
            PageRequest.of(0, request.maxEvents())
        ).getContent();

        int replayed = 0;
        int failed = 0;

        for (DLQDocument doc : events) {
            try {
                EventEnvelope<?> envelope = doc.getOriginalEvent();
                kafkaTemplate.send(doc.getOriginalTopic(), envelope.aggregateId(), envelope);
                replayed++;
            } catch (Exception e) {
                failed++;
                log.error("Failed to replay event {}: {}", doc.getId(), e.getMessage());
            }
        }

        return ResponseEntity.ok(new BatchReplayResult(replayed, failed));
    }

    /**
     * Archive old DLQ events
     */
    @DeleteMapping("/archive")
    public ResponseEntity<ArchiveResult> archiveOldEvents(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate olderThan
    ) {
        long count = dlqRepository.archiveOlderThan(olderThan);
        return ResponseEntity.ok(new ArchiveResult(count));
    }
}
```

---

## 4. Completion Tracking

### 4.1 Batch Execution Tracker

```java
/**
 * Tracks completion status of batch rule executions.
 * Enables knowing when ALL events have been processed.
 */
@Document(collection = "batch_executions")
@Data
public class BatchExecution {

    @Id
    private String batchId;

    // Batch info
    private String triggeredBy;           // "SCHEDULER", "MANUAL", "RETRY"
    private LocalDate executionDate;
    private Instant startedAt;
    private Instant completedAt;          // null until complete

    // Counts
    private int totalEvents;
    private int processedEvents;          // success + failure + skipped
    private int successCount;
    private int failureCount;
    private int skippedCount;
    private int pendingRetryCount;

    // Status
    private BatchStatus status;           // IN_PROGRESS, COMPLETED, PARTIALLY_FAILED

    // Tracking individual events
    private Set<String> pendingCorrelationIds;    // Events still processing
    private Set<String> completedCorrelationIds;  // Successfully processed
    private Set<String> failedCorrelationIds;     // Non-retryable failures
    private Set<String> retryingCorrelationIds;   // Scheduled for retry

    // Failures detail
    private List<FailureRecord> failures;

    public enum BatchStatus {
        IN_PROGRESS,
        COMPLETED,
        PARTIALLY_FAILED,
        FAILED
    }

    @Data
    public static class FailureRecord {
        private String correlationId;
        private String ruleId;
        private String errorCode;
        private String errorMessage;
        private boolean retryable;
        private Instant failedAt;
    }

    public boolean isComplete() {
        return processedEvents >= totalEvents ||
               (pendingCorrelationIds.isEmpty() && retryingCorrelationIds.isEmpty());
    }

    public double getProgress() {
        return totalEvents > 0 ? (double) processedEvents / totalEvents : 0;
    }
}
```

### 4.2 Completion Tracking Service

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchCompletionTracker {

    private final BatchExecutionRepository batchRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final NotificationPublisher notificationPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Start tracking a new batch execution
     */
    @Transactional
    public BatchExecution startBatch(
            String batchId,
            String triggeredBy,
            LocalDate executionDate,
            List<String> correlationIds
    ) {
        BatchExecution batch = new BatchExecution();
        batch.setBatchId(batchId);
        batch.setTriggeredBy(triggeredBy);
        batch.setExecutionDate(executionDate);
        batch.setStartedAt(Instant.now());
        batch.setTotalEvents(correlationIds.size());
        batch.setProcessedEvents(0);
        batch.setStatus(BatchStatus.IN_PROGRESS);
        batch.setPendingCorrelationIds(new HashSet<>(correlationIds));
        batch.setCompletedCorrelationIds(new HashSet<>());
        batch.setFailedCorrelationIds(new HashSet<>());
        batch.setRetryingCorrelationIds(new HashSet<>());
        batch.setFailures(new ArrayList<>());

        return batchRepository.save(batch);
    }

    /**
     * Record successful event processing
     */
    @Transactional
    public void recordSuccess(String batchId, String correlationId) {
        batchRepository.findById(batchId).ifPresent(batch -> {
            batch.getPendingCorrelationIds().remove(correlationId);
            batch.getRetryingCorrelationIds().remove(correlationId);
            batch.getCompletedCorrelationIds().add(correlationId);
            batch.setSuccessCount(batch.getSuccessCount() + 1);
            batch.setProcessedEvents(batch.getProcessedEvents() + 1);

            checkCompletion(batch);
            batchRepository.save(batch);
        });
    }

    /**
     * Record failed event (non-retryable)
     */
    @Transactional
    public void recordFailure(
            String batchId,
            String correlationId,
            String ruleId,
            RuleExecutionFailure failure
    ) {
        batchRepository.findById(batchId).ifPresent(batch -> {
            batch.getPendingCorrelationIds().remove(correlationId);
            batch.getRetryingCorrelationIds().remove(correlationId);
            batch.getFailedCorrelationIds().add(correlationId);
            batch.setFailureCount(batch.getFailureCount() + 1);
            batch.setProcessedEvents(batch.getProcessedEvents() + 1);

            batch.getFailures().add(new FailureRecord(
                correlationId,
                ruleId,
                failure.errorCode(),
                failure.errorMessage(),
                failure.retryable(),
                failure.failedAt()
            ));

            checkCompletion(batch);
            batchRepository.save(batch);
        });
    }

    /**
     * Record event scheduled for retry
     */
    @Transactional
    public void recordRetryScheduled(String batchId, String correlationId) {
        batchRepository.findById(batchId).ifPresent(batch -> {
            batch.getPendingCorrelationIds().remove(correlationId);
            batch.getRetryingCorrelationIds().add(correlationId);
            batch.setPendingRetryCount(batch.getPendingRetryCount() + 1);

            batchRepository.save(batch);
        });
    }

    /**
     * Check if batch is complete and trigger notifications
     */
    private void checkCompletion(BatchExecution batch) {
        if (batch.isComplete()) {
            batch.setCompletedAt(Instant.now());
            batch.setStatus(determineStatus(batch));

            // Publish completion notification
            publishBatchCompletedNotification(batch);

            log.info("Batch {} completed: status={}, success={}, failed={}, retrying={}",
                batch.getBatchId(),
                batch.getStatus(),
                batch.getSuccessCount(),
                batch.getFailureCount(),
                batch.getRetryingCorrelationIds().size()
            );
        }
    }

    private BatchStatus determineStatus(BatchExecution batch) {
        if (batch.getFailureCount() == 0 && batch.getRetryingCorrelationIds().isEmpty()) {
            return BatchStatus.COMPLETED;
        } else if (batch.getSuccessCount() > 0) {
            return BatchStatus.PARTIALLY_FAILED;
        } else {
            return BatchStatus.FAILED;
        }
    }

    private void publishBatchCompletedNotification(BatchExecution batch) {
        // Get unique userIds from failed rules
        Set<String> affectedUsers = batch.getFailures().stream()
            .map(f -> getUserIdFromRule(f.getRuleId()))
            .collect(Collectors.toSet());

        for (String userId : affectedUsers) {
            BatchCompletedNotification notification = new BatchCompletedNotification(
                userId,
                batch.getBatchId(),
                batch.getExecutionDate(),
                batch.getSuccessCount(),
                batch.getFailureCount(),
                batch.getSkippedCount(),
                getTopFailures(batch, userId, 5),
                Instant.now()
            );

            notificationPublisher.publish(
                "recurring_rules.notifications",
                userId,
                notification
            );
        }
    }

    /**
     * Query method to check batch completion status
     */
    public BatchExecutionStatus getStatus(String batchId) {
        return batchRepository.findById(batchId)
            .map(batch -> new BatchExecutionStatus(
                batch.getBatchId(),
                batch.getStatus(),
                batch.isComplete(),
                batch.getProgress(),
                batch.getTotalEvents(),
                batch.getProcessedEvents(),
                batch.getSuccessCount(),
                batch.getFailureCount(),
                batch.getPendingRetryCount(),
                batch.getStartedAt(),
                batch.getCompletedAt()
            ))
            .orElse(null);
    }

    /**
     * Wait for batch completion with timeout
     */
    public CompletableFuture<BatchExecutionStatus> waitForCompletion(
            String batchId,
            Duration timeout
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Instant deadline = Instant.now().plus(timeout);

            while (Instant.now().isBefore(deadline)) {
                BatchExecutionStatus status = getStatus(batchId);
                if (status != null && status.isComplete()) {
                    return status;
                }

                try {
                    Thread.sleep(100); // Poll every 100ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Wait interrupted", e);
                }
            }

            throw new TimeoutException("Batch did not complete within " + timeout);
        });
    }
}
```

### 4.3 Completion Status API

```java
@RestController
@RequestMapping("/api/v1/recurring-rules/executions")
@RequiredArgsConstructor
public class BatchExecutionController {

    private final BatchCompletionTracker completionTracker;

    @GetMapping("/batch/{batchId}")
    public ResponseEntity<BatchExecutionStatus> getBatchStatus(
            @PathVariable String batchId
    ) {
        BatchExecutionStatus status = completionTracker.getStatus(batchId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/batch/{batchId}/wait")
    public DeferredResult<BatchExecutionStatus> waitForCompletion(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "30") int timeoutSeconds
    ) {
        DeferredResult<BatchExecutionStatus> result = new DeferredResult<>(
            Duration.ofSeconds(timeoutSeconds).toMillis()
        );

        completionTracker.waitForCompletion(batchId, Duration.ofSeconds(timeoutSeconds))
            .whenComplete((status, error) -> {
                if (error != null) {
                    result.setErrorResult(error);
                } else {
                    result.setResult(status);
                }
            });

        return result;
    }

    @GetMapping("/today")
    public List<BatchExecutionSummary> getTodayExecutions() {
        return completionTracker.getExecutionsForDate(LocalDate.now());
    }
}
```

---

## 5. CashFlow Service Changes

### 5.1 New Kafka Listener

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleCommandListener {

    private final CashFlowService cashFlowService;
    private final CategoryService categoryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
        topics = "recurring_rules.commands",
        groupId = "cashflow-rule-executor",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleRuleCommand(
            ConsumerRecord<String, String> record,
            Acknowledgment ack
    ) {
        String cashFlowId = record.key();
        EventEnvelope<?> envelope;

        try {
            envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        } catch (Exception e) {
            log.error("Failed to deserialize event from partition={}, offset={}",
                record.partition(), record.offset(), e);
            sendToDLQ(record, "DESERIALIZATION_ERROR", e);
            ack.acknowledge();
            return;
        }

        try (var ignored = MDC.putCloseable("correlationId", envelope.correlationId());
             var ignored2 = MDC.putCloseable("eventId", envelope.eventId())) {

            log.info("Processing {} for cashFlow={}",
                envelope.eventType(), cashFlowId);

            Instant startTime = Instant.now();

            Object result = switch (envelope.eventType()) {
                case "CreateCashChangeIntent" ->
                    handleCreateIntent(envelope.payload(CreateCashChangeIntent.class));
                case "UpdateCashChangeIntent" ->
                    handleUpdateIntent(envelope.payload(UpdateCashChangeIntent.class));
                case "DeleteCashChangeIntent" ->
                    handleDeleteIntent(envelope.payload(DeleteCashChangeIntent.class));
                default -> {
                    log.warn("Unknown event type: {}", envelope.eventType());
                    yield null;
                }
            };

            if (result != null) {
                Duration processingTime = Duration.between(startTime, Instant.now());
                publishResult(envelope, result, processingTime);
                recordMetrics(envelope.eventType(), "SUCCESS", processingTime);
            }

            ack.acknowledge();

        } catch (RuleExecutionFailure failure) {
            publishFailure(envelope, failure);
            recordMetrics(envelope.eventType(), failure.errorCode(), null);
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Unexpected error processing event {}", envelope.eventId(), e);
            sendToDLQ(record, "UNKNOWN_ERROR", e);
            ack.acknowledge();
        }
    }

    private Object handleCreateIntent(CreateCashChangeIntent intent) {
        // 1. Validate CashFlow exists and is OPEN
        CashFlow cashFlow = cashFlowService.findById(new CashFlowId(intent.cashFlowId()))
            .orElseThrow(() -> new CashFlowNotFoundFailure(
                MDC.get("correlationId"),
                intent.ruleId(),
                intent.cashFlowId(),
                intent.idempotencyKey(),
                Instant.now()
            ));

        if (cashFlow.getStatus() != CashFlowStatus.OPEN) {
            throw new CashFlowClosedFailure(
                MDC.get("correlationId"),
                intent.ruleId(),
                intent.cashFlowId(),
                intent.idempotencyKey(),
                cashFlow.getClosedAt(),
                Instant.now()
            );
        }

        // 2. Validate category
        Optional<Category> category = categoryService.findByName(
            intent.cashFlowId(), intent.categoryName()
        );

        if (category.isEmpty()) {
            throw new CategoryNotFoundFailure(
                MDC.get("correlationId"),
                intent.ruleId(),
                intent.cashFlowId(),
                intent.idempotencyKey(),
                intent.categoryName(),
                Instant.now()
            );
        }

        if (category.get().isArchived()) {
            throw new CategoryArchivedFailure(
                MDC.get("correlationId"),
                intent.ruleId(),
                intent.cashFlowId(),
                intent.idempotencyKey(),
                intent.categoryName(),
                category.get().getArchivedAt(),
                Instant.now()
            );
        }

        // 3. Check idempotency
        Optional<CashChange> existing = cashFlowService.findByIdempotencyKey(
            intent.cashFlowId(), intent.idempotencyKey()
        );

        if (existing.isPresent()) {
            return new IdempotentResult(
                intent.idempotencyKey(),
                existing.get().getId().id(),
                intent.ruleId(),
                intent.cashFlowId(),
                existing.get().getCreatedAt(),
                "CashChange already exists for this idempotency key"
            );
        }

        // 4. Create CashChange
        CashChange cashChange = cashFlowService.createCashChange(
            new CashFlowId(intent.cashFlowId()),
            new CreateCashChangeCommand(
                intent.name(),
                intent.description(),
                new Money(intent.amount().amount(), intent.amount().currency()),
                Type.valueOf(intent.type()),
                new CategoryName(intent.categoryName()),
                intent.dueDate(),
                intent.ruleId(),           // sourceRuleId
                intent.idempotencyKey()
            )
        );

        return new CashChangeCreatedResult(
            cashChange.getId().id(),
            intent.cashFlowId(),
            intent.ruleId(),
            intent.idempotencyKey(),
            cashChange.getName(),
            cashChange.getCategoryName().name(),
            new MoneyDto(cashChange.getAmount()),
            cashChange.getType().name(),
            cashChange.getStatus().name(),
            cashChange.getDueDate(),
            cashChange.getCreatedAt(),
            null // processingTime set by caller
        );
    }

    private void publishResult(
            EventEnvelope<?> originalEnvelope,
            Object result,
            Duration processingTime
    ) {
        String eventType = result.getClass().getSimpleName();

        EventEnvelope<Object> resultEnvelope = new EventEnvelope<>(
            UUID.randomUUID().toString(),
            originalEnvelope.correlationId(),    // Same correlationId
            originalEnvelope.eventId(),          // This event caused the result
            eventType,
            "CashFlow",
            originalEnvelope.aggregateId(),
            Instant.now(),
            "cashflow-service",
            1,
            originalEnvelope.batchId(),
            originalEnvelope.batchSequence(),
            originalEnvelope.batchSize(),
            originalEnvelope.testMetadata(),
            result
        );

        kafkaTemplate.send(
            "recurring_rules.results",
            originalEnvelope.aggregateId(),      // Key = cashFlowId
            objectMapper.writeValueAsString(resultEnvelope)
        );
    }

    private void publishFailure(
            EventEnvelope<?> originalEnvelope,
            RuleExecutionFailure failure
    ) {
        EventEnvelope<RuleExecutionFailure> failureEnvelope = new EventEnvelope<>(
            UUID.randomUUID().toString(),
            originalEnvelope.correlationId(),
            originalEnvelope.eventId(),
            failure.getClass().getSimpleName(),
            "CashFlow",
            originalEnvelope.aggregateId(),
            Instant.now(),
            "cashflow-service",
            1,
            originalEnvelope.batchId(),
            originalEnvelope.batchSequence(),
            originalEnvelope.batchSize(),
            originalEnvelope.testMetadata(),
            failure
        );

        kafkaTemplate.send(
            "recurring_rules.results",
            originalEnvelope.aggregateId(),
            objectMapper.writeValueAsString(failureEnvelope)
        );
    }
}
```

---

## 6. WebSocket Integration

### 6.1 WebSocket Gateway Updates

```java
// Add to KafkaEventConsumer.java

@KafkaListener(
    topics = "recurring_rules.notifications",
    groupId = "websocket-gateway",
    containerFactory = "kafkaEventListenerContainerFactory"
)
public void consumeRecurringRulesNotification(KafkaEvent event) {
    log.debug("Received recurring_rules notification: type={}, userId={}",
        event.getEventType(), event.getUserId());

    eventBroadcaster.broadcastToUser(event.getUserId(), event);
}
```

### 6.2 Frontend WebSocket Client

```typescript
// Example frontend subscription for recurring rules notifications
interface RecurringRuleNotification {
  type: 'SUCCESS' | 'FAILURE' | 'RULE_PAUSED' | 'BATCH_COMPLETED';
  ruleId: string;
  ruleName: string;
  cashFlowId: string;
  title: string;
  message: string;
  actionUrl?: string;
  metadata?: Record<string, any>;
  timestamp: string;
}

class RecurringRulesWebSocket {
  private ws: WebSocket;

  connect(userId: string) {
    this.ws = new WebSocket(`wss://api.vidulum.com/ws/events`);

    this.ws.onopen = () => {
      // Subscribe to recurring rules notifications
      this.ws.send(JSON.stringify({
        type: 'subscribe',
        topic: 'recurring_rules.notifications',
        filters: { userId }
      }));
    };

    this.ws.onmessage = (event) => {
      const message = JSON.parse(event.data);

      if (message.topic === 'recurring_rules.notifications') {
        this.handleNotification(message.data as RecurringRuleNotification);
      }
    };
  }

  private handleNotification(notification: RecurringRuleNotification) {
    switch (notification.type) {
      case 'SUCCESS':
        toast.success(notification.title, {
          description: notification.message,
          action: notification.actionUrl ? {
            label: 'View',
            onClick: () => router.push(notification.actionUrl)
          } : undefined
        });
        // Refresh relevant views
        queryClient.invalidateQueries(['cashflow', notification.cashFlowId]);
        break;

      case 'FAILURE':
        toast.error(notification.title, {
          description: notification.message,
          duration: 10000  // Longer for errors
        });
        // Refresh rule list to show paused status
        queryClient.invalidateQueries(['recurring-rules']);
        break;

      case 'RULE_PAUSED':
        toast.warning(`Rule "${notification.ruleName}" was paused`, {
          description: notification.message
        });
        queryClient.invalidateQueries(['recurring-rules', notification.ruleId]);
        break;

      case 'BATCH_COMPLETED':
        // Show batch summary
        showBatchSummaryModal(notification);
        break;
    }
  }
}
```

---

## 7. Test Metadata and Tracing

### 7.1 Test Metadata in Events

```java
/**
 * Test metadata that flows through the entire event chain.
 * Used for assertions in integration tests.
 */
public record TestMetadata(
    String testId,                    // Unique test run ID
    String testName,                  // Test method name
    String testClass,                 // Test class name
    Map<String, String> assertions,   // Key-value pairs for assertions
    Instant testStartedAt
) {
    public static TestMetadata forTest(String testName) {
        return new TestMetadata(
            UUID.randomUUID().toString(),
            testName,
            Thread.currentThread().getStackTrace()[2].getClassName(),
            new HashMap<>(),
            Instant.now()
        );
    }

    public TestMetadata withAssertion(String key, String expectedValue) {
        Map<String, String> newAssertions = new HashMap<>(assertions);
        newAssertions.put(key, expectedValue);
        return new TestMetadata(testId, testName, testClass, newAssertions, testStartedAt);
    }
}
```

### 7.2 Test Event Capture

```java
/**
 * Captures events during tests for assertions.
 */
@Component
@ConditionalOnProperty(name = "testing.event-capture.enabled", havingValue = "true")
public class TestEventCapture {

    private final Map<String, List<EventEnvelope<?>>> capturedEvents =
        new ConcurrentHashMap<>();

    @KafkaListener(
        topics = {"recurring_rules.commands", "recurring_rules.results"},
        groupId = "test-event-capture-#{T(java.util.UUID).randomUUID()}"
    )
    public void captureEvent(EventEnvelope<?> event) {
        if (event.testMetadata() != null) {
            String testId = event.testMetadata().get("testId");
            capturedEvents.computeIfAbsent(testId, k -> new CopyOnWriteArrayList<>())
                .add(event);
        }
    }

    public List<EventEnvelope<?>> getEventsForTest(String testId) {
        return capturedEvents.getOrDefault(testId, List.of());
    }

    public <T> Optional<EventEnvelope<T>> findEvent(
            String testId,
            String eventType,
            Class<T> payloadType
    ) {
        return getEventsForTest(testId).stream()
            .filter(e -> e.eventType().equals(eventType))
            .findFirst()
            .map(e -> (EventEnvelope<T>) e);
    }

    public void awaitEvent(
            String testId,
            String eventType,
            Duration timeout
    ) throws TimeoutException {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            if (findEvent(testId, eventType, Object.class).isPresent()) {
                return;
            }
            Thread.sleep(50);
        }

        throw new TimeoutException(
            "Event " + eventType + " not received within " + timeout
        );
    }

    public void clear(String testId) {
        capturedEvents.remove(testId);
    }
}
```

### 7.3 Integration Test Example

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RecurringRulesEDAIntegrationTest {

    @Autowired
    private TestEventCapture eventCapture;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private BatchCompletionTracker completionTracker;

    @Autowired
    private ObjectMapper objectMapper;

    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]");

    @Test
    void shouldCreateCashChangeViaEDA() throws Exception {
        // Given: Test metadata for tracing
        String testId = UUID.randomUUID().toString();
        Map<String, String> testMetadata = Map.of(
            "testId", testId,
            "testName", "shouldCreateCashChangeViaEDA"
        );

        // And: A valid create intent
        CreateCashChangeIntent intent = new CreateCashChangeIntent(
            "RR10000001",
            "Netflix Subscription",
            "CF10000001",
            "U10000001",
            "Entertainment",
            "Netflix - January 2022",
            "Monthly subscription",
            new MoneyDto(BigDecimal.valueOf(15.99), "PLN"),
            "OUTFLOW",
            LocalDate.of(2022, 1, 10),
            "RR10000001-2022-01-10",
            LocalDate.of(2022, 1, 10),
            1
        );

        String correlationId = UUID.randomUUID().toString();

        EventEnvelope<CreateCashChangeIntent> envelope = EventEnvelope.create(
            "CreateCashChangeIntent",
            "RecurringRule",
            "CF10000001",
            intent,
            correlationId,
            null
        ).withTestMetadata(testMetadata);

        // When: Publish intent event
        kafkaTemplate.send(
            "recurring_rules.commands",
            "CF10000001",
            objectMapper.writeValueAsString(envelope)
        ).get(5, TimeUnit.SECONDS);

        // Then: Wait for result event
        eventCapture.awaitEvent(testId, "CashChangeCreatedResult", Duration.ofSeconds(10));

        // And: Verify the result
        Optional<EventEnvelope<CashChangeCreatedResult>> resultEvent =
            eventCapture.findEvent(testId, "CashChangeCreatedResult", CashChangeCreatedResult.class);

        assertThat(resultEvent).isPresent();

        CashChangeCreatedResult result = resultEvent.get().payload();

        assertThat(result)
            .usingRecursiveComparison()
            .ignoringFields("cashChangeId", "createdAt", "processingTime")
            .isEqualTo(new CashChangeCreatedResult(
                null,  // ignored
                "CF10000001",
                "RR10000001",
                "RR10000001-2022-01-10",
                "Netflix - January 2022",
                "Entertainment",
                new MoneyDto(BigDecimal.valueOf(15.99), "PLN"),
                "OUTFLOW",
                "PLANNED",
                LocalDate.of(2022, 1, 10),
                null,  // ignored
                null   // ignored
            ));

        // And: Verify correlation ID is preserved
        assertThat(resultEvent.get().correlationId()).isEqualTo(correlationId);

        // And: Verify causation chain
        assertThat(resultEvent.get().causationId()).isEqualTo(envelope.eventId());

        // Cleanup
        eventCapture.clear(testId);
    }

    @Test
    void shouldHandleBatchExecutionWithMixedResults() throws Exception {
        // Given: A batch of 3 rules
        String batchId = UUID.randomUUID().toString();
        String testId = UUID.randomUUID().toString();

        List<String> correlationIds = List.of(
            UUID.randomUUID().toString(),  // Will succeed
            UUID.randomUUID().toString(),  // Will fail (category not found)
            UUID.randomUUID().toString()   // Will succeed
        );

        // Start batch tracking
        completionTracker.startBatch(batchId, "TEST", LocalDate.now(), correlationIds);

        // Create intents
        List<CreateCashChangeIntent> intents = List.of(
            createValidIntent("RR001", "CF10000001", "Entertainment"),
            createValidIntent("RR002", "CF10000001", "NonExistentCategory"),
            createValidIntent("RR003", "CF10000001", "Entertainment")
        );

        // When: Publish all intents
        for (int i = 0; i < intents.size(); i++) {
            EventEnvelope<CreateCashChangeIntent> envelope = EventEnvelope.create(
                "CreateCashChangeIntent",
                "RecurringRule",
                "CF10000001",
                intents.get(i),
                correlationIds.get(i),
                null
            ).withBatchContext(batchId, i + 1, intents.size())
             .withTestMetadata(Map.of("testId", testId));

            kafkaTemplate.send(
                "recurring_rules.commands",
                "CF10000001",
                objectMapper.writeValueAsString(envelope)
            );
        }

        // Then: Wait for batch completion
        BatchExecutionStatus status = completionTracker.waitForCompletion(
            batchId, Duration.ofSeconds(30)
        ).get();

        // Verify batch results
        assertThat(status.isComplete()).isTrue();
        assertThat(status.status()).isEqualTo(BatchStatus.PARTIALLY_FAILED);
        assertThat(status.successCount()).isEqualTo(2);
        assertThat(status.failureCount()).isEqualTo(1);

        // Verify individual events
        List<EventEnvelope<?>> allEvents = eventCapture.getEventsForTest(testId);

        long successCount = allEvents.stream()
            .filter(e -> e.eventType().equals("CashChangeCreatedResult"))
            .count();
        long failureCount = allEvents.stream()
            .filter(e -> e.eventType().equals("CategoryNotFoundFailure"))
            .count();

        assertThat(successCount).isEqualTo(2);
        assertThat(failureCount).isEqualTo(1);

        // Cleanup
        eventCapture.clear(testId);
    }
}
```

---

## 8. Monitoring and Observability

### 8.1 Metrics

```java
@Component
@RequiredArgsConstructor
public class EDAMetrics {

    private final MeterRegistry meterRegistry;

    // Commands published
    public void recordCommandPublished(String eventType, String cashFlowId) {
        Counter.builder("recurring_rules.commands.published")
            .tag("event_type", eventType)
            .register(meterRegistry)
            .increment();
    }

    // Commands processed
    public void recordCommandProcessed(String eventType, String result, Duration duration) {
        Counter.builder("recurring_rules.commands.processed")
            .tag("event_type", eventType)
            .tag("result", result)  // SUCCESS, FAILURE, DLQ
            .register(meterRegistry)
            .increment();

        Timer.builder("recurring_rules.commands.duration")
            .tag("event_type", eventType)
            .tag("result", result)
            .register(meterRegistry)
            .record(duration);
    }

    // Results received
    public void recordResultReceived(String eventType, boolean success) {
        Counter.builder("recurring_rules.results.received")
            .tag("event_type", eventType)
            .tag("success", String.valueOf(success))
            .register(meterRegistry)
            .increment();
    }

    // DLQ events
    public void recordDLQEvent(String category, String originalTopic) {
        Counter.builder("recurring_rules.dlq.events")
            .tag("category", category)
            .tag("original_topic", originalTopic)
            .register(meterRegistry)
            .increment();
    }

    // Batch metrics
    public void recordBatchCompleted(BatchStatus status, int total, Duration duration) {
        Counter.builder("recurring_rules.batch.completed")
            .tag("status", status.name())
            .register(meterRegistry)
            .increment();

        Gauge.builder("recurring_rules.batch.size", () -> total)
            .register(meterRegistry);

        Timer.builder("recurring_rules.batch.duration")
            .tag("status", status.name())
            .register(meterRegistry)
            .record(duration);
    }

    // Consumer lag
    public void recordConsumerLag(String topic, String groupId, long lag) {
        Gauge.builder("recurring_rules.consumer.lag", () -> lag)
            .tag("topic", topic)
            .tag("group_id", groupId)
            .register(meterRegistry);
    }
}
```

### 8.2 Grafana Dashboard Queries

```promql
# Commands per second by type
sum(rate(recurring_rules_commands_published_total[5m])) by (event_type)

# Success rate
sum(rate(recurring_rules_commands_processed_total{result="SUCCESS"}[5m]))
/
sum(rate(recurring_rules_commands_processed_total[5m]))

# Average processing time
histogram_quantile(0.95, sum(rate(recurring_rules_commands_duration_seconds_bucket[5m])) by (le, event_type))

# DLQ events per hour
sum(increase(recurring_rules_dlq_events_total[1h])) by (category)

# Consumer lag
max(recurring_rules_consumer_lag) by (topic, group_id)

# Batch completion rate
sum(rate(recurring_rules_batch_completed_total{status="COMPLETED"}[1h]))
/
sum(rate(recurring_rules_batch_completed_total[1h]))
```

### 8.3 Alerting Rules

```yaml
groups:
  - name: recurring-rules-eda
    rules:
      # High DLQ rate
      - alert: RecurringRulesHighDLQRate
        expr: sum(rate(recurring_rules_dlq_events_total[5m])) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High DLQ event rate in Recurring Rules"
          description: "More than 1 event/second going to DLQ"

      # Consumer lag too high
      - alert: RecurringRulesConsumerLag
        expr: max(recurring_rules_consumer_lag) > 1000
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Kafka consumer lag too high"
          description: "Consumer lag exceeds 1000 messages"

      # Low success rate
      - alert: RecurringRulesLowSuccessRate
        expr: |
          sum(rate(recurring_rules_commands_processed_total{result="SUCCESS"}[15m]))
          /
          sum(rate(recurring_rules_commands_processed_total[15m])) < 0.9
        for: 15m
        labels:
          severity: critical
        annotations:
          summary: "Low success rate for recurring rule executions"
          description: "Less than 90% of commands are succeeding"

      # Processing time too slow
      - alert: RecurringRulesSlowProcessing
        expr: |
          histogram_quantile(0.95,
            sum(rate(recurring_rules_commands_duration_seconds_bucket[5m])) by (le)
          ) > 5
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Slow command processing"
          description: "95th percentile processing time exceeds 5 seconds"
```

---

## 9. Summary

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              EVENT-DRIVEN ARCHITECTURE                           │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────────┐                          ┌─────────────────────┐       │
│  │   RECURRING RULES   │                          │      CASHFLOW       │       │
│  │                     │                          │                     │       │
│  │  ┌───────────────┐  │  recurring_rules.commands│  ┌───────────────┐  │       │
│  │  │ RuleScheduler │──┼──────────────────────────┼─▶│RuleCommandList│  │       │
│  │  └───────────────┘  │                          │  └───────────────┘  │       │
│  │                     │                          │         │           │       │
│  │  ┌───────────────┐  │  recurring_rules.results │         ▼           │       │
│  │  │ResultListener │◀─┼──────────────────────────┼──┌───────────────┐  │       │
│  │  └───────────────┘  │                          │  │CashFlowService│  │       │
│  │         │           │                          │  └───────────────┘  │       │
│  │         ▼           │                          │                     │       │
│  │  ┌───────────────┐  │                          └─────────────────────┘       │
│  │  │BatchTracker   │  │                                                        │
│  │  └───────────────┘  │                                                        │
│  │                     │                                                        │
│  └─────────────────────┘                                                        │
│                                                                                  │
│  ┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐│
│  │        DLQ          │     │   WEBSOCKET GW      │     │    MONITORING       ││
│  │                     │     │                     │     │                     ││
│  │  recurring_rules.dlq│     │recurring_rules.     │     │ Prometheus/Grafana  ││
│  │  - Manual review    │     │notifications        │     │ - Metrics           ││
│  │  - Replay API       │     │  - Real-time UI     │     │ - Alerts            ││
│  │  - 30-day retention │     │  - Toast messages   │     │ - Dashboards        ││
│  │                     │     │                     │     │                     ││
│  └─────────────────────┘     └─────────────────────┘     └─────────────────────┘│
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Key Benefits

1. **Loose Coupling** - Services communicate only via events
2. **Resilience** - Failures are isolated and retryable
3. **Scalability** - Partitioning by cashFlowId enables parallel processing
4. **Auditability** - Full event history with correlation IDs
5. **Real-time UI** - WebSocket notifications for instant feedback
6. **Testability** - Test metadata enables precise assertions
7. **Observability** - Comprehensive metrics and alerting

### Topics Summary

| Topic | Direction | Partitions | Key | Purpose |
|-------|-----------|------------|-----|---------|
| `recurring_rules.commands` | RR → CF | 12 | cashFlowId | Create/Update/Delete intents |
| `recurring_rules.results` | CF → RR | 12 | cashFlowId | Success/Failure results |
| `recurring_rules.dlq` | Any → Admin | 3 | - | Unprocessable events |
| `recurring_rules.notifications` | Any → WS | 6 | userId | UI notifications |

### Next Steps

1. Review this design with team
2. Create Kafka topics in dev environment
3. Implement EventEnvelope and base event classes
4. Implement CashFlow RuleCommandListener
5. Implement RecurringRules ResultListener
6. Add WebSocket Gateway listener for notifications
7. Implement BatchCompletionTracker
8. Add integration tests with TestEventCapture
9. Deploy to staging and validate
10. Monitor metrics and tune as needed
