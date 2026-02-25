# Bounded Context Integration - Recurring Rules

**Powiązane:** [04-mongodb-schema.md](./04-mongodb-schema.md) | [Następny: 06-exceptions-and-errors.md](./06-exceptions-and-errors.md)

---

## 1. Przegląd integracji

### 1.1 Diagram kontekstów

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              SYSTEM VIDULUM                                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  ┌────────────────────┐                     ┌────────────────────┐             │
│  │                    │                     │                    │             │
│  │  RECURRING RULES   │                     │     CASH FLOW      │             │
│  │   (Upstream)       │                     │   (Downstream)     │             │
│  │                    │                     │                    │             │
│  │  - Rule CRUD       │ ──── HTTP ────────▶ │  - Validate        │             │
│  │  - Scheduling      │      (sync)         │    categories      │             │
│  │  - Generation      │                     │  - Create          │             │
│  │                    │                     │    CashChanges     │             │
│  │                    │ ◀─── Kafka ──────── │                    │             │
│  │                    │      (async)        │  - Category events │             │
│  │                    │                     │  - CashFlow events │             │
│  └────────────────────┘                     └────────────────────┘             │
│           │                                          │                         │
│           │                                          │                         │
│           │ Kafka (async)                            │ Kafka (async)           │
│           ▼                                          ▼                         │
│  ┌────────────────────────────────────────────────────────────────┐           │
│  │                                                                │           │
│  │                  CASH FLOW FORECAST PROCESSOR                  │           │
│  │                       (Downstream)                             │           │
│  │                                                                │           │
│  │  - Consume RuleCreatedEvent → Update forecasts                 │           │
│  │  - Consume RuleUpdatedEvent → Recalculate affected months      │           │
│  │  - Consume RuleDeletedEvent → Remove from forecasts            │           │
│  │  - Consume ExecutionRecordedEvent → Mark as realized           │           │
│  │                                                                │           │
│  └────────────────────────────────────────────────────────────────┘           │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Typy integracji

| Integracja | Typ | Protokół | Kierunek | Cel |
|------------|-----|----------|----------|-----|
| Category validation | Sync | HTTP | RR → CF | Walidacja kategorii przed zapisem reguły |
| CashChange creation | Sync | HTTP | RR → CF | Generowanie transakcji |
| Category events | Async | Kafka | CF → RR | Synchronizacja stanu kategorii |
| CashFlow lifecycle | Async | Kafka | CF → RR | Obsługa zamknięcia/usunięcia CashFlow |
| Rule events | Async | Kafka | RR → CFP | Aktualizacja prognoz |

---

## 2. Integracja HTTP: Recurring Rules → CashFlow

### 2.1 Walidacja kategorii

#### Endpoint CashFlow

```
GET /api/v1/cash-flow/{cashFlowId}/categories
Authorization: Bearer {token}
```

#### Response

```json
{
  "cashFlowId": "CF10000001",
  "categories": {
    "INFLOW": [
      {
        "name": "Salary",
        "parentCategory": null,
        "isArchived": false,
        "validFrom": "2026-01-01T00:00:00Z",
        "validTo": null
      },
      {
        "name": "Bonus",
        "parentCategory": "Salary",
        "isArchived": false,
        "validFrom": "2026-01-01T00:00:00Z",
        "validTo": null
      }
    ],
    "OUTFLOW": [
      {
        "name": "Housing",
        "parentCategory": null,
        "isArchived": false,
        "validFrom": "2026-01-01T00:00:00Z",
        "validTo": null
      },
      {
        "name": "Utilities",
        "parentCategory": null,
        "isArchived": true,
        "validFrom": "2026-01-01T00:00:00Z",
        "validTo": "2026-02-15T10:00:00Z"
      }
    ]
  }
}
```

#### CategoryValidationService

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryValidationService {

    private final ResilientCashFlowHttpClient cashFlowClient;

    public CategoryValidationResult validateCategory(
            CashFlowId cashFlowId,
            CategoryName categoryName,
            Type expectedType
    ) {
        try {
            CashFlowCategoriesResponse categories =
                    cashFlowClient.getCategories(cashFlowId);

            if (categories == null) {
                return new CategoryValidationResult.CashFlowNotFound(cashFlowId);
            }

            List<CategoryInfo> typeCategories = expectedType == Type.INFLOW
                    ? categories.inflow()
                    : categories.outflow();

            Optional<CategoryInfo> category = typeCategories.stream()
                    .filter(c -> c.name().equals(categoryName.name()))
                    .findFirst();

            if (category.isEmpty()) {
                // Check if exists in opposite type
                List<CategoryInfo> oppositeCategories = expectedType == Type.INFLOW
                        ? categories.outflow()
                        : categories.inflow();

                boolean existsInOpposite = oppositeCategories.stream()
                        .anyMatch(c -> c.name().equals(categoryName.name()));

                if (existsInOpposite) {
                    Type actualType = expectedType == Type.INFLOW
                            ? Type.OUTFLOW : Type.INFLOW;
                    return new CategoryValidationResult.TypeMismatch(
                            categoryName.name(), expectedType, actualType
                    );
                }

                return new CategoryValidationResult.NotFound(categoryName.name());
            }

            CategoryInfo cat = category.get();
            if (cat.isArchived()) {
                return new CategoryValidationResult.Archived(categoryName.name());
            }

            return new CategoryValidationResult.Valid(cat);

        } catch (CashFlowServiceUnavailableException e) {
            return new CategoryValidationResult.ServiceUnavailable(e.getMessage());
        }
    }
}

// Result sealed interface
public sealed interface CategoryValidationResult {
    record Valid(CategoryInfo category) implements CategoryValidationResult {}
    record NotFound(String categoryName) implements CategoryValidationResult {}
    record Archived(String categoryName) implements CategoryValidationResult {}
    record TypeMismatch(String categoryName, Type expected, Type actual)
            implements CategoryValidationResult {}
    record CashFlowNotFound(CashFlowId cashFlowId) implements CategoryValidationResult {}
    record ServiceUnavailable(String message) implements CategoryValidationResult {}
}
```

### 2.2 Tworzenie CashChange

#### Endpoint CashFlow

```
POST /api/v1/cash-flow/{cashFlowId}/cash-changes
Authorization: Bearer {token}
X-Idempotency-Key: {ruleId}-{date}
Content-Type: application/json
```

#### Request

```json
{
  "name": "Wynagrodzenie",
  "description": "Generated from recurring rule RR10000001",
  "money": {
    "amount": 8500.00,
    "currency": "PLN"
  },
  "type": "INFLOW",
  "categoryName": "Salary",
  "dueDate": "2026-03-10T00:00:00Z",
  "sourceRuleId": "RR10000001"
}
```

#### Response

```json
{
  "cashChangeId": "CC10000100",
  "cashFlowId": "CF10000001",
  "name": "Wynagrodzenie",
  "status": "PENDING",
  "created": "2026-03-10T06:00:00Z"
}
```

### 2.3 ResilientCashFlowHttpClient

```java
@Service
@Slf4j
public class ResilientCashFlowHttpClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ResilientCashFlowHttpClient(
            WebClient.Builder webClientBuilder,
            @Value("${cashflow.service.url}") String cashFlowUrl,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry
    ) {
        this.webClient = webClientBuilder
                .baseUrl(cashFlowUrl)
                .build();

        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("cashflow");
        this.retry = retryRegistry.retry("cashflow");
    }

    public CashFlowCategoriesResponse getCategories(CashFlowId cashFlowId) {
        Supplier<CashFlowCategoriesResponse> supplier = () ->
            webClient.get()
                .uri("/api/v1/cash-flow/{id}/categories", cashFlowId.id())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> {
                    if (response.statusCode() == HttpStatus.NOT_FOUND) {
                        return Mono.error(new CashFlowNotFoundException(cashFlowId));
                    }
                    return response.bodyToMono(String.class)
                            .flatMap(body -> Mono.error(new CashFlowClientException(body)));
                })
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                    Mono.error(new CashFlowServiceUnavailableException(
                            "CashFlow service returned " + response.statusCode()))
                )
                .bodyToMono(CashFlowCategoriesResponse.class)
                .timeout(Duration.ofSeconds(5))
                .block();

        return Decorators.ofSupplier(supplier)
                .withCircuitBreaker(circuitBreaker)
                .withRetry(retry)
                .decorate()
                .get();
    }

    public CreateCashChangeResponse createCashChange(
            CashFlowId cashFlowId,
            CreateCashChangeRequest request,
            String idempotencyKey
    ) {
        Supplier<CreateCashChangeResponse> supplier = () ->
            webClient.post()
                .uri("/api/v1/cash-flow/{id}/cash-changes", cashFlowId.id())
                .header("X-Idempotency-Key", idempotencyKey)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> {
                    if (response.statusCode() == HttpStatus.CONFLICT) {
                        // Idempotent - already created
                        return response.bodyToMono(CreateCashChangeResponse.class)
                                .flatMap(Mono::just);
                    }
                    return response.bodyToMono(String.class)
                            .flatMap(body -> Mono.error(new CashFlowClientException(body)));
                })
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                    Mono.error(new CashFlowServiceUnavailableException(
                            "CashFlow service returned " + response.statusCode()))
                )
                .bodyToMono(CreateCashChangeResponse.class)
                .timeout(Duration.ofSeconds(10))
                .block();

        return Decorators.ofSupplier(supplier)
                .withCircuitBreaker(circuitBreaker)
                .withRetry(retry)
                .decorate()
                .get();
    }
}
```

### 2.4 Resilience4j Configuration

```yaml
# application.yml
resilience4j:
  circuitbreaker:
    instances:
      cashflow:
        registerHealthIndicator: true
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
        waitDurationInOpenState: 30s
        failureRateThreshold: 50
        eventConsumerBufferSize: 10
        recordExceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - com.multi.vidulum.recurring_rules.infrastructure.CashFlowServiceUnavailableException
        ignoreExceptions:
          - com.multi.vidulum.recurring_rules.infrastructure.CashFlowClientException

  retry:
    instances:
      cashflow:
        maxAttempts: 3
        waitDuration: 1s
        exponentialBackoffMultiplier: 2
        retryExceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - com.multi.vidulum.recurring_rules.infrastructure.CashFlowServiceUnavailableException

  timelimiter:
    instances:
      cashflow:
        timeoutDuration: 5s
        cancelRunningFuture: true
```

---

## 3. Integracja Kafka: CashFlow → Recurring Rules

### 3.1 Eventy z CashFlow

| Event | Źródło | Akcja w Recurring Rules |
|-------|--------|-------------------------|
| `CategoryArchivedEvent` | CashFlow | Wstrzymaj reguły używające kategorii |
| `CategoryUnarchivedEvent` | CashFlow | (Info) - użytkownik może ręcznie wznowić |
| `CategoryRenamedEvent` | CashFlow | Zaktualizuj nazwę kategorii w regułach |
| `CashFlowClosedEvent` | CashFlow | Zakończ wszystkie reguły dla CashFlow |
| `CashFlowDeletedEvent` | CashFlow | Oznacz reguły jako orphan |

### 3.2 CashFlowEventListener

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class CashFlowEventListener {

    private final RecurringRuleRepository ruleRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    @KafkaListener(
        topics = "cash_flow",
        groupId = "recurring-rules-cashflow-listener"
    )
    public void handleCashFlowEvent(String eventJson) {
        try {
            CashFlowEventEnvelope envelope = parseEvent(eventJson);

            switch (envelope.eventType()) {
                case "CategoryArchivedEvent" -> handleCategoryArchived(
                        envelope.payload(CategoryArchivedEvent.class)
                );
                case "CategoryUnarchivedEvent" -> handleCategoryUnarchived(
                        envelope.payload(CategoryUnarchivedEvent.class)
                );
                case "CashFlowClosedEvent" -> handleCashFlowClosed(
                        envelope.payload(CashFlowClosedEvent.class)
                );
                default -> log.debug("Ignoring event type: {}", envelope.eventType());
            }

        } catch (Exception e) {
            log.error("Error processing CashFlow event: {}", e.getMessage(), e);
            // Consider DLQ or manual intervention
        }
    }

    private void handleCategoryArchived(CategoryArchivedEvent event) {
        log.info("Handling CategoryArchivedEvent for category '{}' in CashFlow '{}'",
                event.categoryName(), event.cashFlowId());

        List<RecurringRule> affectedRules = ruleRepository
                .findByCashFlowIdAndCategoryNameAndStatusNot(
                        new CashFlowId(event.cashFlowId()),
                        new CategoryName(event.categoryName()),
                        RuleStatus.DELETED
                );

        if (affectedRules.isEmpty()) {
            log.debug("No active rules using category '{}'", event.categoryName());
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        for (RecurringRule rule : affectedRules) {
            rule.handleCategoryArchived(now);
            ruleRepository.save(rule);

            // Notify user
            notificationService.notifyRuleAutoPaused(
                    rule.getUserId(),
                    rule.getRuleId(),
                    rule.getName(),
                    event.categoryName(),
                    "Category was archived"
            );
        }

        log.info("Paused {} rules due to category '{}' being archived",
                affectedRules.size(), event.categoryName());
    }

    private void handleCategoryUnarchived(CategoryUnarchivedEvent event) {
        log.info("Category '{}' unarchived in CashFlow '{}'. " +
                 "Users can manually resume affected rules.",
                 event.categoryName(), event.cashFlowId());

        // Find paused rules that were auto-paused due to this category
        List<RecurringRule> pausedRules = ruleRepository
                .findByCashFlowIdAndCategoryNameAndStatus(
                        new CashFlowId(event.cashFlowId()),
                        new CategoryName(event.categoryName()),
                        RuleStatus.PAUSED
                );

        for (RecurringRule rule : pausedRules) {
            if (rule.getPauseInfo() != null &&
                rule.getPauseInfo().reason().contains("archived")) {
                // Notify user they can resume
                notificationService.notifyCategoryAvailableAgain(
                        rule.getUserId(),
                        rule.getRuleId(),
                        rule.getName(),
                        event.categoryName()
                );
            }
        }
    }

    private void handleCashFlowClosed(CashFlowClosedEvent event) {
        log.info("CashFlow '{}' closed. Completing all associated rules.",
                event.cashFlowId());

        List<RecurringRule> activeRules = ruleRepository
                .findByCashFlowIdAndStatusIn(
                        new CashFlowId(event.cashFlowId()),
                        List.of(RuleStatus.ACTIVE, RuleStatus.PAUSED)
                );

        ZonedDateTime now = ZonedDateTime.now(clock);

        for (RecurringRule rule : activeRules) {
            rule.apply(new RecurringRuleEvent.RuleCompletedEvent(
                    rule.getRuleId(),
                    "CashFlow was closed",
                    now
            ));
            ruleRepository.save(rule);
        }

        log.info("Completed {} rules due to CashFlow '{}' closure",
                activeRules.size(), event.cashFlowId());
    }
}
```

### 3.3 Event DTOs

```java
// Event envelope
public record CashFlowEventEnvelope(
    String eventType,
    String cashFlowId,
    ZonedDateTime occurredAt,
    JsonNode payload
) {
    public <T> T payload(Class<T> type) {
        return objectMapper.treeToValue(payload, type);
    }
}

// Specific events
public record CategoryArchivedEvent(
    String cashFlowId,
    String categoryName,
    String categoryType,
    ZonedDateTime archivedAt
) {}

public record CategoryUnarchivedEvent(
    String cashFlowId,
    String categoryName,
    String categoryType,
    ZonedDateTime unarchivedAt
) {}

public record CashFlowClosedEvent(
    String cashFlowId,
    ZonedDateTime closedAt
) {}
```

---

## 4. Integracja Kafka: Recurring Rules → CashFlow Forecast Processor

### 4.1 Eventy publikowane przez Recurring Rules

| Event | Kiedy | Wpływ na forecast |
|-------|-------|-------------------|
| `RuleCreatedEvent` | Utworzenie reguły | Dodaj przyszłe wystąpienia do prognoz |
| `RuleUpdatedEvent` | Edycja reguły | Przelicz dotknięte miesiące |
| `RuleDeletedEvent` | Usunięcie reguły | Usuń przyszłe wystąpienia z prognoz |
| `RulePausedEvent` | Wstrzymanie | Oznacz jako wstrzymane (nie usuwaj) |
| `RuleResumedEvent` | Wznowienie | Przywróć do prognoz |
| `AmountChangeAddedEvent` | Dodanie zmiany kwoty | Zaktualizuj kwoty dla dotkniętych dat |
| `ExecutionRecordedEvent` | Wykonanie reguły | Oznacz jako zrealizowane |

### 4.2 Kafka Topic Configuration

```yaml
# Topic: recurring_rules
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 1

kafka:
  topics:
    recurring-rules:
      name: recurring_rules
      partitions: 6
      replication-factor: 3
      retention-ms: 604800000  # 7 days
```

### 4.3 Event Publisher

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringRuleEventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void publishEvents(RecurringRule rule) {
        List<RecurringRuleEvent> events = rule.getUncommittedEvents();

        for (RecurringRuleEvent event : events) {
            OutboxDocument outboxEntry = new OutboxDocument();
            outboxEntry.setId(UUID.randomUUID());
            outboxEntry.setAggregateType("RecurringRule");
            outboxEntry.setAggregateId(rule.getRuleId().id());
            outboxEntry.setEventType(event.getClass().getSimpleName());
            outboxEntry.setPayload(serializeEvent(event));
            outboxEntry.setTargetTopic("recurring_rules");
            outboxEntry.setStatus("PENDING");
            outboxEntry.setCreatedAt(ZonedDateTime.now());

            outboxRepository.save(outboxEntry);
        }

        rule.markEventsAsCommitted();
    }

    private Map<String, Object> serializeEvent(RecurringRuleEvent event) {
        // Serialize to Map for flexible JSON storage
        return objectMapper.convertValue(event, new TypeReference<>() {});
    }
}
```

### 4.4 CashFlow Forecast Processor Handler

```java
// W module cashflow_forecast_processor
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringRuleEventHandler {

    private final ForecastRepository forecastRepository;
    private final ForecastCalculator forecastCalculator;

    @KafkaListener(
        topics = "recurring_rules",
        groupId = "cashflow-forecast-processor"
    )
    public void handleRecurringRuleEvent(String eventJson) {
        try {
            RecurringRuleEventEnvelope envelope = parseEvent(eventJson);

            switch (envelope.eventType()) {
                case "RuleCreatedEvent" -> handleRuleCreated(
                        envelope.payload(RuleCreatedEventDto.class)
                );
                case "RuleUpdatedEvent" -> handleRuleUpdated(
                        envelope.payload(RuleUpdatedEventDto.class)
                );
                case "RuleDeletedEvent" -> handleRuleDeleted(
                        envelope.payload(RuleDeletedEventDto.class)
                );
                case "ExecutionRecordedEvent" -> handleExecutionRecorded(
                        envelope.payload(ExecutionRecordedEventDto.class)
                );
                default -> log.debug("Ignoring event type: {}", envelope.eventType());
            }

        } catch (Exception e) {
            log.error("Error processing RecurringRule event: {}", e.getMessage(), e);
        }
    }

    private void handleRuleCreated(RuleCreatedEventDto event) {
        log.info("Processing RuleCreatedEvent for rule '{}' in CashFlow '{}'",
                event.ruleId(), event.cashFlowId());

        // Calculate all future occurrences
        List<ForecastEntry> futureOccurrences = forecastCalculator
                .calculateOccurrences(event, 12);  // Next 12 months

        // Add to forecasts
        for (ForecastEntry entry : futureOccurrences) {
            forecastRepository.addRecurringEntry(
                    new CashFlowId(event.cashFlowId()),
                    entry.period(),
                    entry
            );
        }

        log.info("Added {} forecast entries for rule '{}'",
                futureOccurrences.size(), event.ruleId());
    }

    private void handleRuleUpdated(RuleUpdatedEventDto event) {
        log.info("Processing RuleUpdatedEvent for rule '{}'", event.ruleId());

        // Remove old entries
        forecastRepository.removeEntriesBySourceRule(
                new CashFlowId(event.cashFlowId()),
                new RecurringRuleId(event.ruleId())
        );

        // Recalculate with new parameters
        List<ForecastEntry> newOccurrences = forecastCalculator
                .calculateOccurrences(event, 12);

        for (ForecastEntry entry : newOccurrences) {
            forecastRepository.addRecurringEntry(
                    new CashFlowId(event.cashFlowId()),
                    entry.period(),
                    entry
            );
        }
    }

    private void handleRuleDeleted(RuleDeletedEventDto event) {
        log.info("Processing RuleDeletedEvent for rule '{}'", event.ruleId());

        forecastRepository.removeEntriesBySourceRule(
                new CashFlowId(event.cashFlowId()),
                new RecurringRuleId(event.ruleId())
        );
    }

    private void handleExecutionRecorded(ExecutionRecordedEventDto event) {
        if (event.status() != ExecutionStatus.SUCCESS) {
            return;
        }

        log.info("Marking forecast entry as realized for rule '{}' on '{}'",
                event.ruleId(), event.scheduledDate());

        forecastRepository.markAsRealized(
                new CashFlowId(event.cashFlowId()),
                new RecurringRuleId(event.ruleId()),
                event.scheduledDate(),
                new CashChangeId(event.generatedCashChangeId())
        );
    }
}
```

---

## 5. Diagram sekwencji - pełny przepływ

### 5.1 Tworzenie reguły z aktualizacją prognoz

```
┌────────┐  ┌─────────────┐  ┌──────────┐  ┌─────────┐  ┌────────────┐  ┌─────────────┐
│  User  │  │ Recurring   │  │ CashFlow │  │ MongoDB │  │   Kafka    │  │  Forecast   │
│        │  │ Rules API   │  │  Service │  │         │  │            │  │  Processor  │
└───┬────┘  └──────┬──────┘  └────┬─────┘  └────┬────┘  └─────┬──────┘  └──────┬──────┘
    │              │              │             │             │                │
    │ POST /rule   │              │             │             │                │
    ├─────────────▶│              │             │             │                │
    │              │              │             │             │                │
    │              │ GET /categories            │             │                │
    │              ├─────────────▶│             │             │                │
    │              │              │             │             │                │
    │              │ 200 OK       │             │             │                │
    │              │◀─────────────┤             │             │                │
    │              │              │             │             │                │
    │              │ Validate category          │             │                │
    │              │──────────────────────────▶ │             │                │
    │              │              │             │             │                │
    │              │ Save rule                  │             │                │
    │              │────────────────────────────┼────────────▶│                │
    │              │              │             │             │                │
    │              │ Save outbox entry          │             │                │
    │              │────────────────────────────┼────────────▶│                │
    │              │              │             │             │                │
    │ 201 Created  │              │             │             │                │
    │◀─────────────┤              │             │             │                │
    │              │              │             │             │                │
    │              │              │             │   [Outbox   │                │
    │              │              │             │  Processor] │                │
    │              │              │             │      │      │                │
    │              │              │             │      ▼      │                │
    │              │              │             │  Send event │                │
    │              │              │             │─────────────┼───────────────▶│
    │              │              │             │             │                │
    │              │              │             │             │                │
    │              │              │             │             │ Add to         │
    │              │              │             │             │ forecasts      │
    │              │              │             │             │◀───────────────┤
    │              │              │             │             │                │
```

### 5.2 Archiwizacja kategorii

```
┌──────────┐  ┌─────────┐  ┌─────────────┐  ┌──────────────┐  ┌────────┐
│ CashFlow │  │  Kafka  │  │  Recurring  │  │ Notification │  │  User  │
│   API    │  │         │  │Rules Listener│ │   Service    │  │        │
└────┬─────┘  └────┬────┘  └──────┬──────┘  └──────┬───────┘  └───┬────┘
     │             │              │                │              │
     │ Archive     │              │                │              │
     │ category    │              │                │              │
     │─────────────│              │                │              │
     │             │              │                │              │
     │ Publish     │              │                │              │
     │ event       │              │                │              │
     │────────────▶│              │                │              │
     │             │              │                │              │
     │             │ Consume      │                │              │
     │             │─────────────▶│                │              │
     │             │              │                │              │
     │             │              │ Find affected  │              │
     │             │              │ rules          │              │
     │             │              │────────────────│              │
     │             │              │                │              │
     │             │              │ Pause rules    │              │
     │             │              │────────────────│              │
     │             │              │                │              │
     │             │              │ Notify user    │              │
     │             │              │───────────────▶│              │
     │             │              │                │              │
     │             │              │                │ Email/       │
     │             │              │                │ Push         │
     │             │              │                │─────────────▶│
     │             │              │                │              │
```

---

## 6. Anti-Corruption Layer

### 6.1 Cel

Recurring Rules używa własnego modelu domenowego niezależnego od CashFlow. ACL tłumaczy między modelami.

### 6.2 Implementacja

```java
@Component
public class CashFlowAntiCorruptionLayer {

    /**
     * Tłumaczy odpowiedź z CashFlow na wewnętrzny model kategorii
     */
    public List<CategoryInfo> translateCategories(
            CashFlowCategoriesResponse response,
            Type type
    ) {
        List<CategoryDto> sourceCats = type == Type.INFLOW
                ? response.categories().inflow()
                : response.categories().outflow();

        return sourceCats.stream()
                .map(this::toInternalCategory)
                .toList();
    }

    private CategoryInfo toInternalCategory(CategoryDto dto) {
        return new CategoryInfo(
                dto.name(),
                dto.parentCategory(),
                dto.isArchived(),
                dto.validFrom() != null ? dto.validFrom() : ZonedDateTime.now(),
                dto.validTo()
        );
    }

    /**
     * Tłumaczy wewnętrzny request na format CashFlow
     */
    public CreateCashChangeExternalRequest translateCreateRequest(
            RecurringRule rule,
            LocalDate dueDate,
            Money effectiveAmount
    ) {
        return new CreateCashChangeExternalRequest(
                rule.getName().value(),
                "Generated from recurring rule " + rule.getRuleId().id(),
                new MoneyDto(effectiveAmount.amount(), effectiveAmount.currency()),
                rule.getType().name(),
                rule.getCategoryName().name(),
                dueDate.atStartOfDay(ZoneId.systemDefault()),
                rule.getRuleId().id()  // sourceRuleId
        );
    }

    /**
     * Tłumaczy event z CashFlow na wewnętrzny format
     */
    public CategoryArchivedInternalEvent translateCategoryArchivedEvent(
            CategoryArchivedEvent externalEvent
    ) {
        return new CategoryArchivedInternalEvent(
                new CashFlowId(externalEvent.cashFlowId()),
                new CategoryName(externalEvent.categoryName()),
                Type.valueOf(externalEvent.categoryType()),
                externalEvent.archivedAt()
        );
    }
}
```

---

## 7. Idempotentność

### 7.1 Create Rule Idempotency

```java
@Service
@RequiredArgsConstructor
public class CreateRecurringRuleCommandHandler
        implements CommandHandler<CreateRecurringRuleCommand, RecurringRuleResponse> {

    private final RecurringRuleRepository repository;

    @Override
    @Transactional
    public RecurringRuleResponse handle(CreateRecurringRuleCommand command) {
        // Check idempotency key
        if (command.idempotencyKey() != null) {
            Optional<RecurringRule> existing = repository
                    .findByIdempotencyKey(command.idempotencyKey());

            if (existing.isPresent()) {
                log.info("Returning existing rule for idempotency key: {}",
                        command.idempotencyKey());
                return toResponse(existing.get());
            }
        }

        // ... create new rule
    }
}
```

### 7.2 Execution Idempotency

```java
@Service
public class RuleExecutionService {

    private static final String IDEMPOTENCY_KEY_FORMAT = "%s-%s";  // ruleId-date

    public void executeRule(RecurringRule rule, LocalDate date) {
        // Check if already executed
        if (rule.isExecutedFor(date)) {
            log.info("Rule {} already executed for {}, skipping",
                    rule.getRuleId(), date);
            return;
        }

        String idempotencyKey = String.format(IDEMPOTENCY_KEY_FORMAT,
                rule.getRuleId().id(), date.toString());

        // Create CashChange with idempotency key
        CreateCashChangeResponse response = cashFlowClient.createCashChange(
                rule.getCashFlowId(),
                buildRequest(rule, date),
                idempotencyKey
        );

        // Record execution even if it was idempotent return
        rule.recordExecution(date, ExecutionStatus.SUCCESS,
                new CashChangeId(response.cashChangeId()), null, ZonedDateTime.now());
    }
}
```

---

## 8. Testowanie integracji

### 8.1 Contract Tests (Pact)

```java
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "CashFlowService")
class CashFlowClientContractTest {

    @Pact(consumer = "RecurringRulesService")
    public RequestResponsePact getCategoriesPact(PactDslWithProvider builder) {
        return builder
                .given("CashFlow CF10000001 exists with categories")
                .uponReceiving("a request for categories")
                .path("/api/v1/cash-flow/CF10000001/categories")
                .method("GET")
                .willRespondWith()
                .status(200)
                .body(new PactDslJsonBody()
                        .stringValue("cashFlowId", "CF10000001")
                        .object("categories")
                        .array("INFLOW")
                        .object()
                        .stringValue("name", "Salary")
                        .booleanValue("isArchived", false)
                        .closeObject()
                        .closeArray()
                        .closeObject()
                )
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "getCategoriesPact")
    void shouldGetCategories(MockServer mockServer) {
        ResilientCashFlowHttpClient client = new ResilientCashFlowHttpClient(
                WebClient.builder(), mockServer.getUrl(), ...
        );

        CashFlowCategoriesResponse response = client.getCategories(
                new CashFlowId("CF10000001")
        );

        assertThat(response.categories().inflow())
                .anyMatch(c -> c.name().equals("Salary"));
    }
}
```

### 8.2 Integration Tests z Testcontainers

```java
@SpringBootTest
@Testcontainers
class RecurringRulesCashFlowIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"));

    @Container
    static MongoDBContainer mongodb = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

    @Test
    void shouldPauseRuleWhenCategoryArchived() {
        // Given: Active rule using "Salary" category
        RecurringRule rule = createActiveRule("Salary");

        // When: CategoryArchivedEvent is published
        kafkaTemplate.send("cash_flow", """
            {
              "eventType": "CategoryArchivedEvent",
              "cashFlowId": "CF10000001",
              "categoryName": "Salary",
              "archivedAt": "2026-02-25T17:00:00Z"
            }
            """);

        // Then: Rule should be paused
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            RecurringRule updated = ruleRepository.findById(rule.getRuleId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(RuleStatus.PAUSED);
            assertThat(updated.getPauseInfo().reason()).contains("archived");
        });
    }
}
```

---

## Następny dokument

Przejdź do [06-exceptions-and-errors.md](./06-exceptions-and-errors.md) aby zobaczyć katalog wyjątków i błędów.
