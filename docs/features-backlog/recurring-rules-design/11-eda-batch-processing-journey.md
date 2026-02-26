# Batch Processing Journey - Przykłady

## Overview

Ten dokument pokazuje pełne journey batch processing z konkretnymi przykładami czasowymi i stanami systemu.

---

## Scenariusz

Jest **1 marca 2026, godzina 00:00**. System ma wykonać wszystkie reguły zaplanowane na dziś.

### Stan przed batch execution

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         STAN PRZED BATCH EXECUTION                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  User: Jan Kowalski (U10000001)                                            │
│  CashFlow: "Budżet Domowy" (CF10000001)                                    │
│                                                                             │
│  Aktywne reguły na 1 marca 2026:                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ RR10000001 │ Netflix        │ 15.99 PLN  │ OUTFLOW │ Entertainment  │   │
│  │ RR10000002 │ Spotify        │ 19.99 PLN  │ OUTFLOW │ Entertainment  │   │
│  │ RR10000003 │ Wynagrodzenie  │ 8500 PLN   │ INFLOW  │ Salary         │   │
│  │ RR10000004 │ Czynsz         │ 1800 PLN   │ OUTFLOW │ Housing        │   │
│  │ RR10000005 │ Siłownia       │ 99 PLN     │ OUTFLOW │ DeletedCategory│   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Problemy:                                                                  │
│  - RR10000005 używa kategorii "DeletedCategory" która została usunięta    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Faza 1: Scheduler Trigger (00:00:00)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  RECURRING RULES SERVICE                                                      │
│  ════════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  00:00:00.000  [Scheduler] Daily batch execution triggered                   │
│                                                                              │
│  00:00:00.050  [BatchExecutor] Finding rules for 2026-03-01...              │
│                Found 5 active rules                                          │
│                                                                              │
│  00:00:00.100  [BatchExecutor] Creating batch execution:                     │
│                                                                              │
│                BatchExecution {                                              │
│                  batchId: "BATCH-2026-03-01-abc123"                         │
│                  triggeredBy: "SCHEDULER"                                    │
│                  executionDate: 2026-03-01                                   │
│                  totalEvents: 5                                              │
│                  status: IN_PROGRESS                                         │
│                  pendingCorrelationIds: [                                    │
│                    "corr-001", "corr-002", "corr-003", "corr-004", "corr-005"│
│                  ]                                                           │
│                }                                                             │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Java Code

```java
@Scheduled(cron = "0 0 0 * * *")  // Every day at midnight
public void executeDailyBatch() {
    LocalDate today = LocalDate.now();
    String batchId = "BATCH-" + today + "-" + UUID.randomUUID().toString().substring(0, 8);

    log.info("Starting daily batch execution: batchId={}, date={}", batchId, today);

    // Find all rules scheduled for today
    List<RecurringRule> rulesToExecute = ruleRepository.findRulesScheduledFor(today);

    // Generate correlation IDs
    List<String> correlationIds = rulesToExecute.stream()
        .map(rule -> UUID.randomUUID().toString())
        .toList();

    // Start tracking
    batchCompletionTracker.startBatch(batchId, "SCHEDULER", today, correlationIds);

    // Publish intent events
    for (int i = 0; i < rulesToExecute.size(); i++) {
        RecurringRule rule = rulesToExecute.get(i);
        String correlationId = correlationIds.get(i);

        publishCreateIntent(rule, today, batchId, i + 1, rulesToExecute.size(), correlationId);
    }

    log.info("Published {} intent events for batch {}", rulesToExecute.size(), batchId);
}
```

---

## Faza 2: Publishing Intent Events (00:00:00 - 00:00:01)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  KAFKA: recurring_rules.commands                                              │
│  ════════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  Partition 3 (CF10000001 % 12 = 3)                                          │
│  ─────────────────────────────────────────────────────────────────────────── │
│  Offset │ Key         │ Event                                                │
│  ─────────────────────────────────────────────────────────────────────────── │
│  1001   │ CF10000001  │ {                                                    │
│         │             │   eventId: "evt-001"                                 │
│         │             │   correlationId: "corr-001"                          │
│         │             │   eventType: "CreateCashChangeIntent"                │
│         │             │   batchId: "BATCH-2026-03-01-abc123"                │
│         │             │   batchSequence: 1                                   │
│         │             │   batchSize: 5                                       │
│         │             │   payload: {                                         │
│         │             │     ruleId: "RR10000001"                             │
│         │             │     ruleName: "Netflix"                              │
│         │             │     categoryName: "Entertainment"                    │
│         │             │     amount: { amount: 15.99, currency: "PLN" }       │
│         │             │     type: "OUTFLOW"                                  │
│         │             │     dueDate: "2026-03-01"                            │
│         │             │     idempotencyKey: "RR10000001-2026-03-01"          │
│         │             │   }                                                  │
│         │             │ }                                                    │
│  ─────────────────────────────────────────────────────────────────────────── │
│  1002   │ CF10000001  │ { correlationId: "corr-002", RR10000002, Spotify }   │
│  1003   │ CF10000001  │ { correlationId: "corr-003", RR10000003, Wynagrodzenie}│
│  1004   │ CF10000001  │ { correlationId: "corr-004", RR10000004, Czynsz }    │
│  1005   │ CF10000001  │ { correlationId: "corr-005", RR10000005, Siłownia }  │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  Wszystkie eventy na tej samej partycji = ORDERED PROCESSING                │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Partitioning Logic

```java
/**
 * All events for the same CashFlow go to the same partition.
 * This guarantees ordering per CashFlow.
 */
public class CashFlowPartitioner implements Partitioner {

    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        String cashFlowId = (String) key;
        int numPartitions = cluster.partitionCountForTopic(topic);

        // Consistent hashing
        return Math.abs(cashFlowId.hashCode()) % numPartitions;
        // CF10000001.hashCode() % 12 = 3
    }
}
```

---

## Faza 3: CashFlow Processing (00:00:01 - 00:00:03)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  CASHFLOW SERVICE - RuleCommandListener                                       │
│  ════════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  00:00:01.100  [Consumer] Received CreateCashChangeIntent                    │
│                correlationId: "corr-001", ruleId: "RR10000001"              │
│                                                                              │
│  00:00:01.110  [Validator] ✓ CashFlow CF10000001 exists                     │
│  00:00:01.115  [Validator] ✓ CashFlow status = OPEN                         │
│  00:00:01.120  [Validator] ✓ Category "Entertainment" exists                │
│  00:00:01.125  [Validator] ✓ Category is not archived                       │
│  00:00:01.130  [Validator] ✓ Idempotency key not found (new)                │
│                                                                              │
│  00:00:01.200  [Service] Creating CashChange...                              │
│                CashChange {                                                  │
│                  id: "CC10000100"                                            │
│                  name: "Netflix"                                             │
│                  amount: 15.99 PLN                                           │
│                  status: PLANNED                                             │
│                  dueDate: 2026-03-01                                         │
│                  sourceRuleId: "RR10000001"                                  │
│                }                                                             │
│                                                                              │
│  00:00:01.250  [Publisher] → recurring_rules.results                         │
│                CashChangeCreatedResult { cashChangeId: "CC10000100" }        │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  00:00:01.300  [Consumer] Received CreateCashChangeIntent                    │
│                correlationId: "corr-002", ruleId: "RR10000002" (Spotify)    │
│                ... processing ... ✓ SUCCESS                                  │
│                                                                              │
│  00:00:01.500  [Consumer] Received CreateCashChangeIntent                    │
│                correlationId: "corr-003", ruleId: "RR10000003" (Wynagrodzenie)│
│                ... processing ... ✓ SUCCESS                                  │
│                                                                              │
│  00:00:01.700  [Consumer] Received CreateCashChangeIntent                    │
│                correlationId: "corr-004", ruleId: "RR10000004" (Czynsz)      │
│                ... processing ... ✓ SUCCESS                                  │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  00:00:01.900  [Consumer] Received CreateCashChangeIntent                    │
│                correlationId: "corr-005", ruleId: "RR10000005" (Siłownia)    │
│                                                                              │
│  00:00:01.910  [Validator] ✓ CashFlow CF10000001 exists                     │
│  00:00:01.915  [Validator] ✓ CashFlow status = OPEN                         │
│  00:00:01.920  [Validator] ✗ Category "DeletedCategory" NOT FOUND           │
│                                                                              │
│  00:00:01.925  [Publisher] → recurring_rules.results                         │
│                CategoryNotFoundFailure {                                     │
│                  correlationId: "corr-005"                                   │
│                  ruleId: "RR10000005"                                        │
│                  categoryName: "DeletedCategory"                             │
│                  errorCode: "RR004"                                          │
│                  retryable: false                                            │
│                }                                                             │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### CashFlow Listener Code

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleCommandListener {

    private final CashFlowService cashFlowService;
    private final CategoryService categoryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "recurring_rules.commands",
        groupId = "cashflow-rule-executor"
    )
    public void handleRuleCommand(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String cashFlowId = record.key();
        EventEnvelope<?> envelope = parseEnvelope(record.value());

        try (var ignored = MDC.putCloseable("correlationId", envelope.correlationId())) {

            log.info("Processing {} for cashFlow={}", envelope.eventType(), cashFlowId);

            if ("CreateCashChangeIntent".equals(envelope.eventType())) {
                CreateCashChangeIntent intent = envelope.payload(CreateCashChangeIntent.class);

                // Validate
                CashFlow cashFlow = validateCashFlow(intent.cashFlowId());
                validateCategory(cashFlow, intent.categoryName(), intent.type());
                checkIdempotency(intent.cashFlowId(), intent.idempotencyKey());

                // Create
                CashChange created = cashFlowService.createCashChange(/* ... */);

                // Publish success
                publishResult(envelope, new CashChangeCreatedResult(
                    created.getId().id(),
                    intent.cashFlowId(),
                    intent.ruleId(),
                    intent.idempotencyKey(),
                    created.getName(),
                    created.getCategoryName().name(),
                    new MoneyDto(created.getAmount()),
                    created.getType().name(),
                    created.getStatus().name(),
                    created.getDueDate(),
                    created.getCreatedAt(),
                    null
                ));
            }

            ack.acknowledge();

        } catch (RuleExecutionFailure failure) {
            publishFailure(envelope, failure);
            ack.acknowledge();
        }
    }

    private void validateCategory(CashFlow cashFlow, String categoryName, String type) {
        Optional<Category> category = categoryService.findByName(
            cashFlow.getId().id(), categoryName
        );

        if (category.isEmpty()) {
            throw new CategoryNotFoundFailure(
                MDC.get("correlationId"),
                /* ruleId, cashFlowId, idempotencyKey from context */,
                categoryName,
                Instant.now()
            );
        }

        if (category.get().isArchived()) {
            throw new CategoryArchivedFailure(/* ... */);
        }
    }
}
```

---

## Faza 4: Result Processing (00:00:02 - 00:00:04)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  RECURRING RULES SERVICE - ResultListener                                     │
│  ════════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  00:00:02.000  [Consumer] Received CashChangeCreatedResult                   │
│                correlationId: "corr-001"                                     │
│                                                                              │
│                [BatchTracker] Recording success for corr-001                 │
│                BatchExecution {                                              │
│                  processedEvents: 1                                          │
│                  successCount: 1                                             │
│                  pendingCorrelationIds: [corr-002, corr-003, corr-004, corr-005]│
│                  completedCorrelationIds: [corr-001]                         │
│                }                                                             │
│                                                                              │
│  00:00:02.100  [Consumer] Received CashChangeCreatedResult (corr-002)        │
│                [BatchTracker] Recording success... processedEvents: 2        │
│                                                                              │
│  00:00:02.200  [Consumer] Received CashChangeCreatedResult (corr-003)        │
│                [BatchTracker] Recording success... processedEvents: 3        │
│                                                                              │
│  00:00:02.300  [Consumer] Received CashChangeCreatedResult (corr-004)        │
│                [BatchTracker] Recording success... processedEvents: 4        │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  00:00:02.400  [Consumer] Received CategoryNotFoundFailure                   │
│                correlationId: "corr-005"                                     │
│                retryable: false                                              │
│                                                                              │
│                [BatchTracker] Recording failure for corr-005                 │
│                [RuleService] AUTO-PAUSING rule RR10000005                    │
│                              reason: "Category 'DeletedCategory' not found" │
│                                                                              │
│                BatchExecution {                                              │
│                  processedEvents: 5                                          │
│                  successCount: 4                                             │
│                  failureCount: 1                                             │
│                  pendingCorrelationIds: []        ← EMPTY                    │
│                  completedCorrelationIds: [corr-001..004]                    │
│                  failedCorrelationIds: [corr-005]                            │
│                  failures: [{                                                │
│                    ruleId: "RR10000005",                                     │
│                    errorCode: "RR004",                                       │
│                    errorMessage: "Category 'DeletedCategory' not found"     │
│                  }]                                                          │
│                }                                                             │
│                                                                              │
│  00:00:02.450  [BatchTracker] *** BATCH COMPLETE ***                         │
│                status: PARTIALLY_FAILED                                      │
│                progress: 100%                                                │
│                duration: 2.45s                                               │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Result Listener Code

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleResultListener {

    private final BatchCompletionTracker batchTracker;
    private final RecurringRuleRepository ruleRepository;
    private final NotificationPublisher notificationPublisher;

    @KafkaListener(
        topics = "recurring_rules.results",
        groupId = "recurring-rules-result-handler"
    )
    public void handleResult(ConsumerRecord<String, String> record, Acknowledgment ack) {
        EventEnvelope<?> envelope = parseEnvelope(record.value());
        String batchId = envelope.batchId();
        String correlationId = envelope.correlationId();

        try (var ignored = MDC.putCloseable("correlationId", correlationId)) {

            switch (envelope.eventType()) {
                case "CashChangeCreatedResult" -> {
                    CashChangeCreatedResult result = envelope.payload(CashChangeCreatedResult.class);

                    log.info("CashChange {} created for rule {}",
                        result.cashChangeId(), result.ruleId());

                    // Update rule's last execution
                    ruleRepository.recordExecution(
                        new RecurringRuleId(result.ruleId()),
                        LocalDate.now(),
                        ExecutionStatus.SUCCESS,
                        new CashChangeId(result.cashChangeId())
                    );

                    // Track batch progress
                    if (batchId != null) {
                        batchTracker.recordSuccess(batchId, correlationId);
                    }
                }

                case "CategoryNotFoundFailure",
                     "CategoryArchivedFailure",
                     "CashFlowClosedFailure" -> {
                    RuleExecutionFailure failure = envelope.payload(RuleExecutionFailure.class);

                    log.warn("Non-retryable failure for rule {}: {}",
                        failure.ruleId(), failure.errorMessage());

                    // Auto-pause the rule
                    RecurringRule rule = ruleRepository.findById(
                        new RecurringRuleId(failure.ruleId())
                    ).orElseThrow();

                    rule.pause(failure.errorMessage(), null, Instant.now());
                    ruleRepository.save(rule);

                    // Track batch progress
                    if (batchId != null) {
                        batchTracker.recordFailure(batchId, correlationId, failure.ruleId(), failure);
                    }
                }

                case "DatabaseTimeoutFailure",
                     "TransientServiceFailure" -> {
                    RuleExecutionFailure failure = envelope.payload(RuleExecutionFailure.class);

                    log.warn("Retryable failure for rule {}: {}",
                        failure.ruleId(), failure.errorMessage());

                    // Schedule retry
                    scheduleRetry(envelope, failure);

                    if (batchId != null) {
                        batchTracker.recordRetryScheduled(batchId, correlationId);
                    }
                }
            }

            ack.acknowledge();
        }
    }
}
```

### Batch Completion Checker

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchCompletionTracker {

    private final BatchExecutionRepository batchRepository;
    private final NotificationPublisher notificationPublisher;

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

    private void checkCompletion(BatchExecution batch) {
        if (batch.isComplete()) {
            batch.setCompletedAt(Instant.now());
            batch.setStatus(determineStatus(batch));

            Duration duration = Duration.between(batch.getStartedAt(), batch.getCompletedAt());

            log.info("Batch {} completed: status={}, success={}, failed={}, duration={}ms",
                batch.getBatchId(),
                batch.getStatus(),
                batch.getSuccessCount(),
                batch.getFailureCount(),
                duration.toMillis()
            );

            // Notify users
            publishBatchCompletedNotification(batch);
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
}
```

---

## Faza 5: WebSocket Notification (00:00:02.500)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  KAFKA: recurring_rules.notifications                                         │
│  ════════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  {                                                                           │
│    eventType: "BatchCompletedNotification",                                  │
│    userId: "U10000001",                                                      │
│    payload: {                                                                │
│      batchId: "BATCH-2026-03-01-abc123",                                    │
│      executionDate: "2026-03-01",                                            │
│      successCount: 4,                                                        │
│      failureCount: 1,                                                        │
│      skippedCount: 0,                                                        │
│      failures: [{                                                            │
│        ruleId: "RR10000005",                                                 │
│        ruleName: "Siłownia",                                                 │
│        errorCode: "RR004",                                                   │
│        errorMessage: "Category 'DeletedCategory' not found"                  │
│      }],                                                                     │
│      timestamp: "2026-03-01T00:00:02.500Z"                                  │
│    }                                                                         │
│  }                                                                           │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  WEBSOCKET GATEWAY                                                            │
│  ════════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  00:00:02.510  [Consumer] Received BatchCompletedNotification                │
│                userId: "U10000001"                                           │
│                                                                              │
│  00:00:02.515  [SessionRegistry] Finding sessions for U10000001...           │
│                Found 1 active session (mobile app)                           │
│                                                                              │
│  00:00:02.520  [WebSocket] → Sending to session ws-session-xyz               │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  MOBILE APP (User: Jan Kowalski)                                              │
│  ════════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  ┌────────────────────────────────────────┐                                  │
│  │  🔔 Push Notification                  │                                  │
│  │  ────────────────────────────────────  │                                  │
│  │  Recurring Rules: 4/5 executed         │                                  │
│  │                                        │                                  │
│  │  ⚠️ 1 rule failed: "Siłownia"          │                                  │
│  │     Category not found                 │                                  │
│  │                                        │                                  │
│  │  [View Details]  [Dismiss]             │                                  │
│  └────────────────────────────────────────┘                                  │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### WebSocket Gateway Code

```java
// Add to KafkaEventConsumer.java in websocket-gateway

@KafkaListener(
    topics = "recurring_rules.notifications",
    groupId = "websocket-gateway",
    containerFactory = "kafkaEventListenerContainerFactory"
)
public void consumeRecurringRulesNotification(KafkaEvent event) {
    String userId = event.getUserId();

    log.debug("Received notification for user {}: type={}",
        userId, event.getEventType());

    // Find all active sessions for this user
    Set<WebSocketSession> sessions = sessionRegistry.getSessionsForUser(userId);

    if (sessions.isEmpty()) {
        log.debug("No active sessions for user {}", userId);
        return;
    }

    // Broadcast to all user's devices
    ServerMessage message = ServerMessage.event(
        "recurring_rules.notifications",
        event.getEventType(),
        null,  // No specific cashFlowId
        event.getPayload()
    );

    for (WebSocketSession session : sessions) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException e) {
            log.warn("Failed to send to session {}: {}", session.getId(), e.getMessage());
        }
    }
}
```

### Frontend Handler

```typescript
// React/TypeScript example
interface BatchCompletedNotification {
  batchId: string;
  executionDate: string;
  successCount: number;
  failureCount: number;
  skippedCount: number;
  failures: Array<{
    ruleId: string;
    ruleName: string;
    errorCode: string;
    errorMessage: string;
  }>;
  timestamp: string;
}

function useRecurringRulesNotifications() {
  const queryClient = useQueryClient();

  useEffect(() => {
    const ws = new WebSocket('wss://api.vidulum.com/ws/events');

    ws.onopen = () => {
      ws.send(JSON.stringify({
        type: 'subscribe',
        topic: 'recurring_rules.notifications'
      }));
    };

    ws.onmessage = (event) => {
      const message = JSON.parse(event.data);

      if (message.eventType === 'BatchCompletedNotification') {
        const notification = message.data as BatchCompletedNotification;

        // Show toast
        if (notification.failureCount > 0) {
          toast.warning(
            `Recurring Rules: ${notification.successCount}/${notification.successCount + notification.failureCount} executed`,
            {
              description: `${notification.failureCount} rule(s) failed`,
              action: {
                label: 'View Details',
                onClick: () => router.push('/recurring-rules?filter=failed')
              }
            }
          );
        } else {
          toast.success(
            `All ${notification.successCount} recurring rules executed successfully`
          );
        }

        // Invalidate queries to refresh UI
        queryClient.invalidateQueries(['recurring-rules']);
        queryClient.invalidateQueries(['cashflow', 'transactions']);
      }
    };

    return () => ws.close();
  }, []);
}
```

---

## Faza 6: Stan końcowy (00:00:03)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         STAN PO BATCH EXECUTION                               │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  CashFlow: "Budżet Domowy" (CF10000001)                                     │
│                                                                              │
│  NOWE CASHCHANGES (utworzone przez batch):                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ CC10000100 │ Netflix        │ -15.99 PLN │ PLANNED │ Entertainment  │    │
│  │ CC10000101 │ Spotify        │ -19.99 PLN │ PLANNED │ Entertainment  │    │
│  │ CC10000102 │ Wynagrodzenie  │ +8500 PLN  │ PLANNED │ Salary         │    │
│  │ CC10000103 │ Czynsz         │ -1800 PLN  │ PLANNED │ Housing        │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  REGUŁY - zaktualizowany stan:                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ RR10000001 │ Netflix        │ ACTIVE  │ lastExec: 2026-03-01 ✓      │    │
│  │ RR10000002 │ Spotify        │ ACTIVE  │ lastExec: 2026-03-01 ✓      │    │
│  │ RR10000003 │ Wynagrodzenie  │ ACTIVE  │ lastExec: 2026-03-01 ✓      │    │
│  │ RR10000004 │ Czynsz         │ ACTIVE  │ lastExec: 2026-03-01 ✓      │    │
│  │ RR10000005 │ Siłownia       │ PAUSED  │ reason: Category not found  │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  BATCH EXECUTION RECORD:                                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ batchId: BATCH-2026-03-01-abc123                                    │    │
│  │ status: PARTIALLY_FAILED                                            │    │
│  │ startedAt: 2026-03-01T00:00:00.100Z                                │    │
│  │ completedAt: 2026-03-01T00:00:02.450Z                              │    │
│  │ duration: 2.35s                                                     │    │
│  │ successCount: 4                                                     │    │
│  │ failureCount: 1                                                     │    │
│  │ progress: 100%                                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Scenariusz 2: Retry z Database Timeout

### Timeline

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  SCENARIUSZ: MongoDB chwilowo niedostępne                                     │
│  ════════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  00:00:01.500  [CashFlow] Processing RR10000003 (Wynagrodzenie)              │
│                                                                              │
│  00:00:01.510  [Validator] ✓ All validations pass                           │
│                                                                              │
│  00:00:01.520  [MongoDB] Attempting to insert CashChange...                  │
│                                                                              │
│  00:00:06.520  [MongoDB] ✗ TIMEOUT after 5000ms                             │
│                                                                              │
│  00:00:06.525  [Publisher] → recurring_rules.results                         │
│                DatabaseTimeoutFailure {                                      │
│                  correlationId: "corr-003"                                   │
│                  ruleId: "RR10000003"                                        │
│                  operation: "INSERT_CASH_CHANGE"                             │
│                  timeout: "PT5S"                                             │
│                  attemptNumber: 1                                            │
│                  retryable: true        ← RETRYABLE!                         │
│                }                                                             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  00:00:06.600  [RecurringRules] Received DatabaseTimeoutFailure              │
│                correlationId: "corr-003"                                     │
│                retryable: true                                               │
│                attemptNumber: 1                                              │
│                                                                              │
│                [RetryScheduler] Scheduling retry:                            │
│                  delay: 2^1 * 1000ms = 2000ms                               │
│                  retryAt: 00:00:08.600                                       │
│                                                                              │
│                [BatchTracker] Recording retry for corr-003                   │
│                BatchExecution {                                              │
│                  pendingCorrelationIds: []                                   │
│                  retryingCorrelationIds: [corr-003]    ← MOVED TO RETRY     │
│                  pendingRetryCount: 1                                        │
│                }                                                             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  00:00:08.600  [RetryScheduler] Executing retry for corr-003                 │
│                                                                              │
│  00:00:08.610  [Publisher] → recurring_rules.commands                        │
│                CreateCashChangeIntent {                                      │
│                  correlationId: "corr-003"       ← SAME correlation ID      │
│                  eventId: "evt-003-retry-1"      ← NEW event ID             │
│                  executionAttempt: 2                                         │
│                }                                                             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  00:00:08.700  [CashFlow] Processing retry attempt 2                         │
│                                                                              │
│  00:00:08.750  [MongoDB] ✓ Insert successful (DB recovered)                 │
│                                                                              │
│  00:00:08.760  [Publisher] → recurring_rules.results                         │
│                CashChangeCreatedResult {                                     │
│                  correlationId: "corr-003"                                   │
│                  cashChangeId: "CC10000102"                                  │
│                }                                                             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  00:00:08.800  [RecurringRules] Received CashChangeCreatedResult             │
│                correlationId: "corr-003"                                     │
│                                                                              │
│                [BatchTracker] Recording success (after retry)                │
│                BatchExecution {                                              │
│                  retryingCorrelationIds: []      ← CLEARED                  │
│                  completedCorrelationIds: [..., corr-003]                    │
│                  successCount: 4 (was 3)                                     │
│                }                                                             │
│                                                                              │
│  00:00:08.850  [BatchTracker] *** BATCH COMPLETE ***                         │
│                status: PARTIALLY_FAILED (1 non-retryable failure)            │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Retry Scheduler Code

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class RetryScheduler {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 1000;

    public void scheduleRetry(EventEnvelope<?> originalEnvelope, RuleExecutionFailure failure) {
        int currentAttempt = failure.attemptNumber();

        if (currentAttempt >= MAX_RETRIES) {
            log.warn("Max retries ({}) exceeded for correlationId={}, moving to DLQ",
                MAX_RETRIES, originalEnvelope.correlationId());
            sendToDLQ(originalEnvelope, failure);
            return;
        }

        // Exponential backoff: 2^attempt * 1000ms
        long delayMs = (long) Math.pow(2, currentAttempt) * BASE_DELAY_MS;

        log.info("Scheduling retry {} for correlationId={} in {}ms",
            currentAttempt + 1, originalEnvelope.correlationId(), delayMs);

        scheduler.schedule(() -> {
            try {
                // Re-publish with incremented attempt
                CreateCashChangeIntent originalIntent =
                    originalEnvelope.payload(CreateCashChangeIntent.class);

                CreateCashChangeIntent retryIntent = new CreateCashChangeIntent(
                    originalIntent.ruleId(),
                    originalIntent.ruleName(),
                    originalIntent.cashFlowId(),
                    originalIntent.userId(),
                    originalIntent.categoryName(),
                    originalIntent.name(),
                    originalIntent.description(),
                    originalIntent.amount(),
                    originalIntent.type(),
                    originalIntent.dueDate(),
                    originalIntent.idempotencyKey(),
                    originalIntent.scheduledDate(),
                    currentAttempt + 1  // Incremented attempt
                );

                EventEnvelope<CreateCashChangeIntent> retryEnvelope = new EventEnvelope<>(
                    UUID.randomUUID().toString(),  // New event ID
                    originalEnvelope.correlationId(),  // SAME correlation ID
                    originalEnvelope.eventId(),  // Caused by original
                    "CreateCashChangeIntent",
                    originalEnvelope.aggregateType(),
                    originalEnvelope.aggregateId(),
                    Instant.now(),
                    "recurring-rules-service",
                    1,
                    originalEnvelope.batchId(),
                    originalEnvelope.batchSequence(),
                    originalEnvelope.batchSize(),
                    originalEnvelope.testMetadata(),
                    retryIntent
                );

                kafkaTemplate.send(
                    "recurring_rules.commands",
                    originalIntent.cashFlowId(),
                    objectMapper.writeValueAsString(retryEnvelope)
                );

                log.info("Retry {} published for correlationId={}",
                    currentAttempt + 1, originalEnvelope.correlationId());

            } catch (Exception e) {
                log.error("Failed to publish retry for correlationId={}: {}",
                    originalEnvelope.correlationId(), e.getMessage());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}
```

---

## Scenariusz 3: Query API w trakcie batch

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  FRONTEND: Admin Dashboard                                                    │
│  ════════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  00:00:01.000  [Admin] GET /api/v1/recurring-rules/executions/today          │
│                                                                              │
│                Response:                                                     │
│                [{                                                            │
│                  batchId: "BATCH-2026-03-01-abc123",                        │
│                  status: "IN_PROGRESS",                                      │
│                  progress: 0.4,                 ← 40% done                   │
│                  totalEvents: 5,                                             │
│                  processedEvents: 2,                                         │
│                  successCount: 2,                                            │
│                  failureCount: 0,                                            │
│                  startedAt: "2026-03-01T00:00:00.100Z"                      │
│                }]                                                            │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  00:00:01.500  [Admin] GET /api/v1/recurring-rules/executions/batch/         │
│                         BATCH-2026-03-01-abc123/wait?timeoutSeconds=30       │
│                                                                              │
│                [Server] DeferredResult - waiting for completion...           │
│                                                                              │
│  00:00:02.450  [BatchTracker] Batch complete - resolving DeferredResult      │
│                                                                              │
│                Response (after 0.95s):                                       │
│                {                                                             │
│                  batchId: "BATCH-2026-03-01-abc123",                        │
│                  status: "PARTIALLY_FAILED",                                 │
│                  isComplete: true,                                           │
│                  progress: 1.0,                                              │
│                  totalEvents: 5,                                             │
│                  processedEvents: 5,                                         │
│                  successCount: 4,                                            │
│                  failureCount: 1,                                            │
│                  pendingRetryCount: 0,                                       │
│                  startedAt: "2026-03-01T00:00:00.100Z",                      │
│                  completedAt: "2026-03-01T00:00:02.450Z"                    │
│                }                                                             │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Batch Status API

```java
@RestController
@RequestMapping("/api/v1/recurring-rules/executions")
@RequiredArgsConstructor
public class BatchExecutionController {

    private final BatchCompletionTracker completionTracker;

    /**
     * Get today's batch executions
     */
    @GetMapping("/today")
    public List<BatchExecutionSummary> getTodayExecutions() {
        return completionTracker.getExecutionsForDate(LocalDate.now());
    }

    /**
     * Get specific batch status
     */
    @GetMapping("/batch/{batchId}")
    public ResponseEntity<BatchExecutionStatus> getBatchStatus(@PathVariable String batchId) {
        return completionTracker.getStatus(batchId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Long-polling endpoint - wait for batch completion
     */
    @GetMapping("/batch/{batchId}/wait")
    public DeferredResult<BatchExecutionStatus> waitForCompletion(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "30") int timeoutSeconds
    ) {
        DeferredResult<BatchExecutionStatus> result = new DeferredResult<>(
            Duration.ofSeconds(timeoutSeconds).toMillis()
        );

        // Check if already complete
        BatchExecutionStatus currentStatus = completionTracker.getStatus(batchId);
        if (currentStatus != null && currentStatus.isComplete()) {
            result.setResult(currentStatus);
            return result;
        }

        // Wait for completion
        completionTracker.waitForCompletion(batchId, Duration.ofSeconds(timeoutSeconds))
            .whenComplete((status, error) -> {
                if (error != null) {
                    if (error instanceof TimeoutException) {
                        // Return current status on timeout
                        result.setResult(completionTracker.getStatus(batchId));
                    } else {
                        result.setErrorResult(error);
                    }
                } else {
                    result.setResult(status);
                }
            });

        return result;
    }

    /**
     * Get execution history for a rule
     */
    @GetMapping("/rule/{ruleId}/history")
    public List<RuleExecutionRecord> getRuleHistory(
            @PathVariable String ruleId,
            @RequestParam(defaultValue = "30") int days
    ) {
        LocalDate from = LocalDate.now().minusDays(days);
        return completionTracker.getExecutionHistoryForRule(ruleId, from);
    }
}
```

---

## Podsumowanie Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         BATCH EXECUTION FLOW                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────────────┐     ┌───────────────────┐         │
│  │ SCHEDULER │     │  recurring_rules  │     │     CASHFLOW      │         │
│  │           │     │     .commands     │     │                   │         │
│  │  00:00    │────▶│                   │────▶│  Validate + Create│         │
│  │  trigger  │     │  Intent Events    │     │  CashChanges      │         │
│  └───────────┘     └───────────────────┘     └─────────┬─────────┘         │
│                                                        │                    │
│                                                        ▼                    │
│  ┌───────────┐     ┌───────────────────┐     ┌───────────────────┐         │
│  │  BATCH    │     │  recurring_rules  │     │   Success/Fail    │         │
│  │ TRACKER   │◀────│     .results      │◀────│   Results         │         │
│  │           │     │                   │     │                   │         │
│  │ Track     │     │  Result Events    │     └───────────────────┘         │
│  │ progress  │     └───────────────────┘                                   │
│  └─────┬─────┘                                                             │
│        │                                                                    │
│        │ isComplete?                                                        │
│        ▼                                                                    │
│  ┌───────────┐     ┌───────────────────┐     ┌───────────────────┐         │
│  │ NOTIFY    │────▶│  recurring_rules  │────▶│  WEBSOCKET GW     │         │
│  │           │     │   .notifications  │     │                   │         │
│  │ User      │     │                   │     │  Push to user     │         │
│  └───────────┘     └───────────────────┘     └───────────────────┘         │
│                                                                             │
│  Timeline: ~2-3 seconds for 5 rules                                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Key Features

| Feature | Benefit |
|---------|---------|
| **Partitioning by cashFlowId** | Ordering gwarantowany dla tego samego CashFlow |
| **Batch progress tracking** | Wiesz ile % done w każdym momencie |
| **Exponential backoff retry** | Retryable failures automatycznie ponawiane |
| **Real-time WebSocket notifications** | User dostaje powiadomienie natychmiast |
| **Long-polling API** | Admin może czekać na completion bez refresha |
| **Audit trail** | Pełna historia w BatchExecution document |

### Timing Summary

| Phase | Duration | Description |
|-------|----------|-------------|
| Scheduler trigger | ~100ms | Find rules, create batch, generate correlation IDs |
| Publish intents | ~500ms | Send 5 events to Kafka |
| CashFlow processing | ~1500ms | Validate and create CashChanges |
| Result processing | ~500ms | Update batch tracker, auto-pause failed rules |
| WebSocket notification | ~50ms | Push to user's device |
| **Total** | **~2.5s** | End-to-end for 5 rules |

---

## Następne kroki

1. Zaimplementować `BatchCompletionTracker` z MongoDB
2. Dodać `RuleCommandListener` do CashFlow service
3. Rozszerzyć WebSocket Gateway o nowy topic
4. Dodać retry scheduler z exponential backoff
5. Stworzyć testy integracyjne z Embedded Kafka
