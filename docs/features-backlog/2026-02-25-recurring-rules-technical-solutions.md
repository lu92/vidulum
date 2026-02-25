# Recurring Rules - Technical Solutions

**Data utworzenia:** 2026-02-25
**Status:** Propozycje techniczne do problemów z edge cases
**Autor:** Claude Code + User
**Powiązane dokumenty:**
- `2026-02-25-recurring-rules-edge-cases-analysis.md` (analiza problemów)
- `2026-02-25-recurring-rules-microservice-architecture.md` (architektura)
- `2026-02-14-recurring-rule-engine-design.md` (funkcjonalny design)

---

## Spis treści

1. [Category Lifecycle Management](#1-category-lifecycle-management)
2. [Error Handling & Retry Strategy](#2-error-handling--retry-strategy)
3. [Idempotency & Duplicate Prevention](#3-idempotency--duplicate-prevention)
4. [CashFlow Lifecycle Events](#4-cashflow-lifecycle-events)
5. [Orphaned Data Cleanup](#5-orphaned-data-cleanup)
6. [Outbox Pattern Implementation](#6-outbox-pattern-implementation)
7. [Monitoring & Alerting](#7-monitoring--alerting)
8. [Database Schema Changes](#8-database-schema-changes)
9. [API Contracts](#9-api-contracts)
10. [Implementation Order](#10-implementation-order)

---

## 1. Category Lifecycle Management

### 1.1 Problem

Rule przechowuje `categoryName: String`. Kategoria może być:
- Zarchiwizowana
- Renamed (w przyszłości)
- Usunięta (teoretycznie)

### 1.2 Rozwiązanie: Category Validation Service

```java
package com.multi.vidulum.recurring_rules.app;

@Component
@RequiredArgsConstructor
@Slf4j
public class CategoryValidationService {

    private final CashFlowServiceClient cashFlowClient;

    /**
     * Validates category exists and is active for rule creation/update.
     *
     * @throws CategoryDoesNotExistException if category not found
     * @throws CategoryArchivedException if category is archived
     * @throws CategoryTypeMismatchException if category type doesn't match rule type
     */
    public void validateCategory(CashFlowId cashFlowId, String categoryName, Type ruleType) {
        List<CategoryInfo> categories = cashFlowClient.getCategories(cashFlowId, null);

        CategoryInfo category = categories.stream()
            .filter(c -> c.name().equals(categoryName))
            .findFirst()
            .orElseThrow(() -> new CategoryDoesNotExistException(categoryName, cashFlowId));

        if (category.isArchived()) {
            throw new CategoryArchivedException(categoryName, cashFlowId);
        }

        if (category.type() != ruleType) {
            throw new CategoryTypeMismatchException(
                categoryName,
                category.type(),
                ruleType
            );
        }
    }

    /**
     * Checks if category is still valid (for generation).
     * Returns validation result instead of throwing.
     */
    public CategoryValidationResult checkCategory(CashFlowId cashFlowId, String categoryName) {
        try {
            List<CategoryInfo> categories = cashFlowClient.getCategories(cashFlowId, null);

            Optional<CategoryInfo> category = categories.stream()
                .filter(c -> c.name().equals(categoryName))
                .findFirst();

            if (category.isEmpty()) {
                return CategoryValidationResult.notFound(categoryName);
            }

            if (category.get().isArchived()) {
                return CategoryValidationResult.archived(categoryName);
            }

            return CategoryValidationResult.valid(category.get());

        } catch (CashFlowNotFoundException e) {
            return CategoryValidationResult.cashFlowNotFound(cashFlowId);
        } catch (Exception e) {
            return CategoryValidationResult.error(e.getMessage());
        }
    }
}

public sealed interface CategoryValidationResult {
    record Valid(CategoryInfo category) implements CategoryValidationResult {}
    record NotFound(String categoryName) implements CategoryValidationResult {}
    record Archived(String categoryName) implements CategoryValidationResult {}
    record CashFlowNotFound(CashFlowId cashFlowId) implements CategoryValidationResult {}
    record Error(String message) implements CategoryValidationResult {}

    static Valid valid(CategoryInfo c) { return new Valid(c); }
    static NotFound notFound(String name) { return new NotFound(name); }
    static Archived archived(String name) { return new Archived(name); }
    static CashFlowNotFound cashFlowNotFound(CashFlowId id) { return new CashFlowNotFound(id); }
    static Error error(String msg) { return new Error(msg); }

    default boolean isValid() { return this instanceof Valid; }
}
```

### 1.3 Rozwiązanie: Category Archived Event Handler

```java
package com.multi.vidulum.recurring_rules.app.eventhandlers;

@Component
@RequiredArgsConstructor
@Slf4j
public class CategoryArchivedEventHandler {

    private final RecurringRuleRepository ruleRepository;
    private final NotificationService notificationService;

    /**
     * When a category is archived, pause all active rules using it.
     */
    @KafkaListener(
        topics = "cash_flow",
        groupId = "recurring_rules_category",
        containerFactory = "cashFlowEventListenerFactory"
    )
    public void onCashFlowEvent(CashFlowEvent event) {
        switch (event) {
            case CategoryArchivedEvent e -> handleCategoryArchived(e);
            case CategoryUnarchivedEvent e -> handleCategoryUnarchived(e);
            default -> { /* ignore other events */ }
        }
    }

    private void handleCategoryArchived(CategoryArchivedEvent event) {
        log.info("Category archived: {} in cashFlow={}",
            event.categoryName(), event.cashFlowId());

        List<RecurringRule> affectedRules = ruleRepository
            .findByCashFlowIdAndCategoryNameAndStatus(
                event.cashFlowId(),
                event.categoryName(),
                RuleStatus.ACTIVE
            );

        if (affectedRules.isEmpty()) {
            log.debug("No active rules using category {}", event.categoryName());
            return;
        }

        List<String> pausedRuleNames = new ArrayList<>();

        for (RecurringRule rule : affectedRules) {
            rule.pause(PauseReason.CATEGORY_ARCHIVED);
            ruleRepository.save(rule);
            pausedRuleNames.add(rule.getName().value());

            log.info("Auto-paused rule {} due to category {} archived",
                rule.getId(), event.categoryName());
        }

        // Notify user
        notificationService.send(
            NotificationType.RULES_AUTO_PAUSED,
            event.cashFlowId(),
            Map.of(
                "categoryName", event.categoryName().value(),
                "pausedRules", pausedRuleNames,
                "reason", "CATEGORY_ARCHIVED"
            )
        );
    }

    private void handleCategoryUnarchived(CategoryUnarchivedEvent event) {
        log.info("Category unarchived: {} in cashFlow={}",
            event.categoryName(), event.cashFlowId());

        // Find rules that were paused because of this category
        List<RecurringRule> pausedRules = ruleRepository
            .findByCashFlowIdAndCategoryNameAndStatusAndPauseReason(
                event.cashFlowId(),
                event.categoryName(),
                RuleStatus.PAUSED,
                PauseReason.CATEGORY_ARCHIVED
            );

        if (pausedRules.isEmpty()) {
            return;
        }

        // Don't auto-resume - just notify user
        notificationService.send(
            NotificationType.CATEGORY_AVAILABLE,
            event.cashFlowId(),
            Map.of(
                "categoryName", event.categoryName().value(),
                "pausedRules", pausedRules.stream()
                    .map(r -> r.getName().value())
                    .toList(),
                "suggestion", "You can now resume these rules"
            )
        );
    }
}
```

### 1.4 Rozwiązanie: Block Category Archive (alternatywa)

Jeśli chcemy zablokować archiwizację kategorii z active rules:

```java
// W CashFlow module - ArchiveCategoryCommandHandler

@CommandHandler
public void handle(ArchiveCategoryCommand cmd) {
    CashFlow cashFlow = repository.findById(cmd.cashFlowId())
        .orElseThrow();

    // NEW: Check if category is used by active recurring rules
    RecurringRulesInfo rulesInfo = recurringRulesClient.getRulesUsingCategory(
        cmd.cashFlowId(),
        cmd.categoryName()
    );

    if (rulesInfo.hasActiveRules()) {
        throw new CategoryInUseByRulesException(
            cmd.categoryName(),
            rulesInfo.activeRuleNames()
        );
    }

    // Proceed with archive
    cashFlow.archiveCategory(cmd.categoryName());
    repository.save(cashFlow);
}
```

**Nowy endpoint w recurring_rules:**

```java
@GetMapping("/cash-flow/{cashFlowId}/recurring-rules/by-category")
public ResponseEntity<RulesUsingCategoryResponse> getRulesUsingCategory(
    @PathVariable String cashFlowId,
    @RequestParam String categoryName
) {
    List<RecurringRule> rules = ruleRepository
        .findByCashFlowIdAndCategoryName(
            CashFlowId.of(cashFlowId),
            categoryName
        );

    return ResponseEntity.ok(new RulesUsingCategoryResponse(
        rules.stream()
            .filter(r -> r.getStatus() == RuleStatus.ACTIVE)
            .map(r -> r.getName().value())
            .toList(),
        rules.stream()
            .filter(r -> r.getStatus() == RuleStatus.PAUSED)
            .map(r -> r.getName().value())
            .toList()
    ));
}
```

---

## 2. Error Handling & Retry Strategy

### 2.1 Problem

HTTP calls do CashFlow API mogą failować. Potrzebujemy:
- Retry dla transient errors
- Fast-fail dla permanent errors
- Recovery dla failed operations

### 2.2 Rozwiązanie: Resilient HTTP Client

```java
package com.multi.vidulum.recurring_rules.infrastructure;

@Component
@RequiredArgsConstructor
@Slf4j
public class ResilientCashFlowHttpClient implements CashFlowServiceClient {

    private final WebClient webClient;
    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    private static final String RETRY_NAME = "cashFlowApi";
    private static final String CB_NAME = "cashFlowApi";

    @PostConstruct
    void init() {
        // Configure retry
        retryRegistry.retry(RETRY_NAME, RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofSeconds(1))
            .exponentialBackoffMultiplier(2.0)
            .retryOnException(this::isRetryable)
            .build());

        // Configure circuit breaker
        circuitBreakerRegistry.circuitBreaker(CB_NAME, CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(10)
            .build());
    }

    @Override
    public BatchCreateResponse createExpectedCashChangesBatch(
            CashFlowId cashFlowId,
            RecurringRuleId sourceRuleId,
            List<ExpectedCashChangeItem> items
    ) {
        Retry retry = retryRegistry.retry(RETRY_NAME);
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(CB_NAME);

        return Decorators.ofSupplier(() -> doCreateBatch(cashFlowId, sourceRuleId, items))
            .withRetry(retry)
            .withCircuitBreaker(cb)
            .decorate()
            .get();
    }

    private BatchCreateResponse doCreateBatch(
            CashFlowId cashFlowId,
            RecurringRuleId sourceRuleId,
            List<ExpectedCashChangeItem> items
    ) {
        try {
            return webClient.post()
                .uri("/api/v1/cash-flow/{id}/expected-cash-changes/batch", cashFlowId.id())
                .bodyValue(new BatchCreateRequest(sourceRuleId.id(), items))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handle4xxError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handle5xxError)
                .bodyToMono(BatchCreateResponse.class)
                .block(Duration.ofSeconds(30));

        } catch (WebClientResponseException e) {
            throw mapToBusinessException(e);
        }
    }

    private Mono<? extends Throwable> handle4xxError(ClientResponse response) {
        return response.bodyToMono(ApiError.class)
            .flatMap(error -> {
                // 4xx errors are NOT retryable
                return Mono.error(new CashFlowApiException(
                    error.errorCode(),
                    error.message(),
                    response.statusCode().value(),
                    false  // not retryable
                ));
            });
    }

    private Mono<? extends Throwable> handle5xxError(ClientResponse response) {
        return response.bodyToMono(String.class)
            .flatMap(body -> {
                // 5xx errors ARE retryable
                return Mono.error(new CashFlowApiException(
                    "SERVER_ERROR",
                    body,
                    response.statusCode().value(),
                    true  // retryable
                ));
            });
    }

    private boolean isRetryable(Throwable t) {
        if (t instanceof CashFlowApiException e) {
            return e.isRetryable();
        }
        // Network errors are retryable
        if (t instanceof WebClientRequestException) {
            return true;
        }
        // Timeout is retryable
        if (t instanceof TimeoutException) {
            return true;
        }
        return false;
    }

    private RuntimeException mapToBusinessException(WebClientResponseException e) {
        int status = e.getStatusCode().value();

        return switch (status) {
            case 400 -> {
                ApiError error = parseError(e);
                yield switch (error.errorCode()) {
                    case "CATEGORY_NOT_FOUND" -> new CategoryDoesNotExistException(error.details());
                    case "CATEGORY_ARCHIVED" -> new CategoryArchivedException(error.details());
                    case "CASHFLOW_CLOSED" -> new CashFlowClosedException(error.details());
                    default -> new CashFlowValidationException(error.message());
                };
            }
            case 404 -> new CashFlowNotFoundException(e.getMessage());
            case 409 -> new DuplicateTransactionException(e.getMessage()); // idempotency
            default -> new CashFlowApiException("UNKNOWN", e.getMessage(), status, false);
        };
    }
}
```

### 2.3 Rozwiązanie: Generation Status Tracking

```java
package com.multi.vidulum.recurring_rules.domain;

@Getter
@AllArgsConstructor
public class RecurringRule {
    // ... existing fields

    // NEW: Generation tracking
    private GenerationStatus generationStatus;
    private String lastGenerationError;
    private ZonedDateTime lastGenerationAttempt;
    private int consecutiveFailures;
    private YearMonth pendingGenerationUpTo;  // What we're trying to generate

    public enum GenerationStatus {
        IDLE,           // Nothing pending
        PENDING,        // Generation requested, not yet started
        IN_PROGRESS,    // Currently generating
        COMPLETED,      // Last generation succeeded
        FAILED          // Last generation failed
    }

    // State transitions
    public void startGeneration(YearMonth upTo) {
        this.generationStatus = GenerationStatus.IN_PROGRESS;
        this.pendingGenerationUpTo = upTo;
        this.lastGenerationAttempt = ZonedDateTime.now();
    }

    public void completeGeneration(int count, YearMonth upTo) {
        this.generationStatus = GenerationStatus.COMPLETED;
        this.lastGeneratedPeriod = upTo;
        this.generatedCount += count;
        this.consecutiveFailures = 0;
        this.lastGenerationError = null;
        this.pendingGenerationUpTo = null;
    }

    public void failGeneration(String error) {
        this.generationStatus = GenerationStatus.FAILED;
        this.lastGenerationError = error;
        this.consecutiveFailures++;
    }

    public boolean shouldAutoPause() {
        return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
    }

    private static final int MAX_CONSECUTIVE_FAILURES = 5;
}
```

### 2.4 Rozwiązanie: Failed Generation Recovery

```java
package com.multi.vidulum.recurring_rules.app;

@Component
@RequiredArgsConstructor
@Slf4j
public class FailedGenerationRecoveryService {

    private final RecurringRuleRepository ruleRepository;
    private final CashFlowServiceClient cashFlowClient;
    private final RecurringRuleGenerationService generationService;
    private final NotificationService notificationService;

    /**
     * Scheduled job to retry failed generations.
     * Runs every 15 minutes.
     */
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void recoverFailedGenerations() {
        List<RecurringRule> failedRules = ruleRepository
            .findByGenerationStatus(GenerationStatus.FAILED);

        log.info("Found {} rules with failed generation", failedRules.size());

        for (RecurringRule rule : failedRules) {
            processFailedRule(rule);
        }
    }

    private void processFailedRule(RecurringRule rule) {
        // Check if we should give up
        if (rule.shouldAutoPause()) {
            log.warn("Rule {} exceeded max failures ({}), auto-pausing",
                rule.getId(), rule.getConsecutiveFailures());

            rule.pause(PauseReason.GENERATION_FAILED);
            ruleRepository.save(rule);

            notificationService.send(
                NotificationType.RULE_AUTO_PAUSED,
                rule.getCashFlowId(),
                Map.of(
                    "ruleName", rule.getName().value(),
                    "reason", "Exceeded maximum generation failures",
                    "lastError", rule.getLastGenerationError()
                )
            );
            return;
        }

        // Check backoff - don't retry too quickly
        Duration backoff = calculateBackoff(rule.getConsecutiveFailures());
        ZonedDateTime nextRetryTime = rule.getLastGenerationAttempt().plus(backoff);

        if (ZonedDateTime.now().isBefore(nextRetryTime)) {
            log.debug("Rule {} not ready for retry yet, next attempt at {}",
                rule.getId(), nextRetryTime);
            return;
        }

        // Validate before retry
        CategoryValidationResult validation = categoryValidationService.checkCategory(
            rule.getCashFlowId(),
            rule.getCategoryName().value()
        );

        if (!validation.isValid()) {
            handleInvalidCategory(rule, validation);
            return;
        }

        // Retry generation
        try {
            log.info("Retrying generation for rule {} (attempt {})",
                rule.getId(), rule.getConsecutiveFailures() + 1);

            generationService.generateForRule(rule, rule.getPendingGenerationUpTo());

            log.info("Retry succeeded for rule {}", rule.getId());

        } catch (Exception e) {
            log.error("Retry failed for rule {}: {}", rule.getId(), e.getMessage());
            rule.failGeneration(e.getMessage());
            ruleRepository.save(rule);
        }
    }

    private void handleInvalidCategory(RecurringRule rule, CategoryValidationResult validation) {
        String reason = switch (validation) {
            case CategoryValidationResult.NotFound nf ->
                "Category '" + nf.categoryName() + "' no longer exists";
            case CategoryValidationResult.Archived a ->
                "Category '" + a.categoryName() + "' is archived";
            case CategoryValidationResult.CashFlowNotFound cf ->
                "CashFlow no longer exists";
            case CategoryValidationResult.Error e ->
                "Validation error: " + e.message();
            default -> "Unknown validation issue";
        };

        log.warn("Rule {} has invalid category, pausing: {}", rule.getId(), reason);

        PauseReason pauseReason = switch (validation) {
            case CategoryValidationResult.Archived _ -> PauseReason.CATEGORY_ARCHIVED;
            case CategoryValidationResult.CashFlowNotFound _ -> PauseReason.CASHFLOW_DELETED;
            default -> PauseReason.GENERATION_FAILED;
        };

        rule.pause(pauseReason);
        rule.setLastGenerationError(reason);
        ruleRepository.save(rule);

        notificationService.send(
            NotificationType.RULE_AUTO_PAUSED,
            rule.getCashFlowId(),
            Map.of(
                "ruleName", rule.getName().value(),
                "reason", reason
            )
        );
    }

    private Duration calculateBackoff(int failures) {
        // Exponential backoff: 1min, 2min, 4min, 8min, 16min, 30min (cap)
        long minutes = Math.min((long) Math.pow(2, failures), 30);
        return Duration.ofMinutes(minutes);
    }
}
```

---

## 3. Idempotency & Duplicate Prevention

### 3.1 Problem

Retry może spowodować duplikaty transakcji w CashFlow.

### 3.2 Rozwiązanie A: Unique Constraint w MongoDB

```java
// W CashFlow module - dodaj unique index

@Document(collection = "cash_flows")
public class CashFlowEntity {
    // ... existing fields

    @Indexed
    private List<CashChangeEntity> cashChanges;
}

// MongoDB index (create via migration or manual)
/*
db.cash_flows.createIndex(
    {
        "_id": 1,
        "cashChanges.sourceRuleId": 1,
        "cashChanges.dueDate": 1
    },
    {
        unique: true,
        partialFilterExpression: {
            "cashChanges.sourceRuleId": { $ne: null }
        },
        name: "unique_rule_transaction_per_date"
    }
)
*/

// Alternatywnie - osobna kolekcja dla CashChanges:
@Document(collection = "cash_changes")
@CompoundIndex(
    name = "unique_rule_transaction",
    def = "{'cashFlowId': 1, 'sourceRuleId': 1, 'dueDate': 1}",
    unique = true,
    partialFilter = "{'sourceRuleId': {$ne: null}}"
)
public class CashChangeEntity {
    private String id;
    private String cashFlowId;
    private String sourceRuleId;  // nullable
    private LocalDate dueDate;
    // ... other fields
}
```

### 3.3 Rozwiązanie B: Idempotency Key

```java
// W CashFlow API

@PostMapping("/{cashFlowId}/expected-cash-changes/batch")
public ResponseEntity<BatchCreateResponse> createBatch(
    @PathVariable String cashFlowId,
    @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
    @RequestBody BatchCreateRequest request
) {
    // Check if we've seen this request before
    if (idempotencyKey != null) {
        Optional<IdempotentResponse> cached = idempotencyStore.get(idempotencyKey);
        if (cached.isPresent()) {
            log.info("Returning cached response for idempotency key {}", idempotencyKey);
            return ResponseEntity.ok(cached.get().response());
        }
    }

    // Process request
    BatchCreateResponse response = processCreateBatch(cashFlowId, request);

    // Cache response
    if (idempotencyKey != null) {
        idempotencyStore.save(idempotencyKey, response, Duration.ofHours(24));
    }

    return ResponseEntity.ok(response);
}

// Idempotency store
@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStore {

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    @Override
    public void save(String key, Object response, Duration ttl) {
        String json = objectMapper.writeValueAsString(response);
        redis.opsForValue().set("idempotency:" + key, json, ttl);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String json = redis.opsForValue().get("idempotency:" + key);
        if (json == null) return Optional.empty();
        return Optional.of(objectMapper.readValue(json, type));
    }
}
```

### 3.4 Rozwiązanie C: Generation ID w recurring_rules

```java
// Każda operacja generowania ma unique ID

public class RecurringRule {
    // ... existing fields

    private String currentGenerationId;  // UUID for current generation batch

    public String startNewGeneration(YearMonth upTo) {
        this.currentGenerationId = UUID.randomUUID().toString();
        this.generationStatus = GenerationStatus.IN_PROGRESS;
        this.pendingGenerationUpTo = upTo;
        return this.currentGenerationId;
    }
}

// W HTTP client
public BatchCreateResponse createExpectedCashChangesBatch(
        CashFlowId cashFlowId,
        RecurringRuleId sourceRuleId,
        String generationId,  // NEW
        List<ExpectedCashChangeItem> items
) {
    return webClient.post()
        .uri("/api/v1/cash-flow/{id}/expected-cash-changes/batch", cashFlowId.id())
        .header("X-Idempotency-Key", generationId)  // Use generation ID
        .bodyValue(new BatchCreateRequest(sourceRuleId.id(), items))
        .retrieve()
        .bodyToMono(BatchCreateResponse.class)
        .block();
}
```

### 3.5 Rekomendacja

**Użyj kombinacji:**
1. **Unique constraint** w MongoDB (belt) - zapobiega duplikatom na poziomie DB
2. **Idempotency key** (suspenders) - optymalizacja, unika zbędnego przetwarzania

```java
// Handler w CashFlow dla duplicate
@ExceptionHandler(DuplicateKeyException.class)
public ResponseEntity<BatchCreateResponse> handleDuplicateKey(DuplicateKeyException e) {
    // Extract what was already created
    // Return success with existing IDs
    log.info("Duplicate transaction detected, returning existing");
    return ResponseEntity.ok(new BatchCreateResponse(
        existingIds,
        existingIds.size(),
        true  // partial = some already existed
    ));
}
```

---

## 4. CashFlow Lifecycle Events

### 4.1 Problem

CashFlow może być closed lub deleted. Rules muszą reagować.

### 4.2 Rozwiązanie: Event Handler dla CashFlow Lifecycle

```java
package com.multi.vidulum.recurring_rules.app.eventhandlers;

@Component
@RequiredArgsConstructor
@Slf4j
public class CashFlowLifecycleEventHandler {

    private final RecurringRuleRepository ruleRepository;
    private final NotificationService notificationService;

    @KafkaListener(
        topics = "cash_flow",
        groupId = "recurring_rules_lifecycle",
        containerFactory = "cashFlowEventListenerFactory"
    )
    public void onCashFlowEvent(CashFlowEvent event) {
        switch (event) {
            case CashFlowClosedEvent e -> handleCashFlowClosed(e);
            case CashFlowReopenedEvent e -> handleCashFlowReopened(e);
            case CashFlowDeletedEvent e -> handleCashFlowDeleted(e);
            default -> { /* ignore */ }
        }
    }

    /**
     * CashFlow closed → pause all active rules.
     */
    private void handleCashFlowClosed(CashFlowClosedEvent event) {
        log.info("CashFlow {} closed, pausing all active rules", event.cashFlowId());

        List<RecurringRule> activeRules = ruleRepository
            .findByCashFlowIdAndStatus(event.cashFlowId(), RuleStatus.ACTIVE);

        for (RecurringRule rule : activeRules) {
            rule.pause(PauseReason.CASHFLOW_CLOSED);
            ruleRepository.save(rule);
        }

        log.info("Paused {} rules for closed cashFlow {}", activeRules.size(), event.cashFlowId());
    }

    /**
     * CashFlow reopened → notify user about paused rules.
     */
    private void handleCashFlowReopened(CashFlowReopenedEvent event) {
        log.info("CashFlow {} reopened", event.cashFlowId());

        List<RecurringRule> pausedRules = ruleRepository
            .findByCashFlowIdAndStatusAndPauseReason(
                event.cashFlowId(),
                RuleStatus.PAUSED,
                PauseReason.CASHFLOW_CLOSED
            );

        if (pausedRules.isEmpty()) {
            return;
        }

        // Don't auto-resume - notify user
        notificationService.send(
            NotificationType.CASHFLOW_REOPENED_RULES_PAUSED,
            event.cashFlowId(),
            Map.of(
                "pausedRuleCount", pausedRules.size(),
                "pausedRuleNames", pausedRules.stream()
                    .map(r -> r.getName().value())
                    .toList()
            )
        );
    }

    /**
     * CashFlow deleted → hard delete all rules.
     */
    private void handleCashFlowDeleted(CashFlowDeletedEvent event) {
        log.info("CashFlow {} deleted, removing all rules", event.cashFlowId());

        List<RecurringRule> allRules = ruleRepository
            .findByCashFlowId(event.cashFlowId());

        for (RecurringRule rule : allRules) {
            ruleRepository.delete(rule);  // Hard delete
        }

        log.info("Deleted {} rules for deleted cashFlow {}", allRules.size(), event.cashFlowId());
    }
}
```

### 4.3 Nowe eventy w CashFlow (jeśli nie istnieją)

```java
// Dodaj do CashFlowEvent.java

/**
 * CashFlow was closed (archived, read-only).
 */
record CashFlowClosedEvent(
    CashFlowId cashFlowId,
    ZonedDateTime closedAt
) implements CashFlowEvent {
    @Override
    public ZonedDateTime occurredAt() { return closedAt; }
}

/**
 * CashFlow was reopened after being closed.
 */
record CashFlowReopenedEvent(
    CashFlowId cashFlowId,
    ZonedDateTime reopenedAt
) implements CashFlowEvent {
    @Override
    public ZonedDateTime occurredAt() { return reopenedAt; }
}

/**
 * CashFlow was permanently deleted.
 */
record CashFlowDeletedEvent(
    CashFlowId cashFlowId,
    ZonedDateTime deletedAt
) implements CashFlowEvent {
    @Override
    public ZonedDateTime occurredAt() { return deletedAt; }
}
```

---

## 5. Orphaned Data Cleanup

### 5.1 Problem

- Rules mogą wskazywać na nieistniejące CashFlows
- Transactions mogą wskazywać na usunięte rules

### 5.2 Rozwiązanie: Orphan Detection Job

```java
package com.multi.vidulum.recurring_rules.app;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrphanDetectionService {

    private final RecurringRuleRepository ruleRepository;
    private final CashFlowServiceClient cashFlowClient;

    /**
     * Daily job to detect orphaned rules.
     * Runs at 3 AM.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void detectOrphanedRules() {
        log.info("Starting orphan detection job");

        // Get all unique cashFlowIds from rules
        Set<CashFlowId> cashFlowIds = ruleRepository.findAllCashFlowIds();

        int orphanCount = 0;

        for (CashFlowId cashFlowId : cashFlowIds) {
            try {
                // Check if CashFlow still exists
                cashFlowClient.getCashFlowInfo(cashFlowId);
            } catch (CashFlowNotFoundException e) {
                // CashFlow doesn't exist - mark rules as orphaned
                List<RecurringRule> orphanedRules = ruleRepository
                    .findByCashFlowId(cashFlowId);

                for (RecurringRule rule : orphanedRules) {
                    log.warn("Detected orphaned rule {} (cashFlow {} not found)",
                        rule.getId(), cashFlowId);

                    rule.markAsOrphaned();
                    ruleRepository.save(rule);
                    orphanCount++;
                }
            }
        }

        log.info("Orphan detection completed. Found {} orphaned rules", orphanCount);
    }

    /**
     * Cleanup orphaned rules after grace period.
     * Runs weekly on Sunday at 4 AM.
     */
    @Scheduled(cron = "0 0 4 * * SUN")
    public void cleanupOrphanedRules() {
        log.info("Starting orphan cleanup job");

        ZonedDateTime cutoff = ZonedDateTime.now().minusDays(30);

        List<RecurringRule> oldOrphans = ruleRepository
            .findByOrphanedAtBefore(cutoff);

        for (RecurringRule rule : oldOrphans) {
            log.info("Deleting orphaned rule {} (orphaned since {})",
                rule.getId(), rule.getOrphanedAt());
            ruleRepository.delete(rule);
        }

        log.info("Cleanup completed. Deleted {} orphaned rules", oldOrphans.size());
    }
}
```

### 5.3 Rozszerzenie RecurringRule

```java
public class RecurringRule {
    // ... existing fields

    private boolean orphaned;
    private ZonedDateTime orphanedAt;

    public void markAsOrphaned() {
        this.orphaned = true;
        this.orphanedAt = ZonedDateTime.now();
        this.status = RuleStatus.ENDED;
        this.endReason = EndReason.CASHFLOW_DELETED;
    }
}
```

---

## 6. Outbox Pattern Implementation

### 6.1 Problem

Tworzenie rule i generowanie transakcji to dwa kroki. Mogą być niespójne.

### 6.2 Rozwiązanie: Transactional Outbox

```java
package com.multi.vidulum.recurring_rules.infrastructure;

// Outbox entity
@Document(collection = "recurring_rules_outbox")
@Data
public class OutboxEvent {
    @Id
    private String id;
    private String eventType;
    private String aggregateId;
    private String payload;
    private ZonedDateTime createdAt;
    private ZonedDateTime processedAt;
    private int attemptCount;
    private String lastError;
    private OutboxStatus status;

    public enum OutboxStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}

// Outbox repository
public interface OutboxRepository extends MongoRepository<OutboxEvent, String> {
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);
    List<OutboxEvent> findByStatusAndCreatedAtBefore(OutboxStatus status, ZonedDateTime cutoff);
}

// Transactional service
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalRuleService {

    private final RecurringRuleRepository ruleRepository;
    private final OutboxRepository outboxRepository;
    private final MongoTemplate mongoTemplate;

    /**
     * Creates rule and schedules generation atomically.
     */
    @Transactional
    public RecurringRuleId createRule(CreateRecurringRuleCommand cmd) {
        // 1. Create rule with PENDING_GENERATION status
        RecurringRule rule = RecurringRule.create(cmd);
        rule.setGenerationStatus(GenerationStatus.PENDING);

        // 2. Save rule
        ruleRepository.save(rule);

        // 3. Create outbox event (same transaction)
        OutboxEvent outbox = new OutboxEvent();
        outbox.setId(UUID.randomUUID().toString());
        outbox.setEventType("GENERATE_TRANSACTIONS");
        outbox.setAggregateId(rule.getId().id());
        outbox.setPayload(toJson(new GenerateTransactionsPayload(
            rule.getId(),
            rule.getCashFlowId(),
            rule.calculateHorizon()
        )));
        outbox.setCreatedAt(ZonedDateTime.now());
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setAttemptCount(0);

        outboxRepository.save(outbox);

        log.info("Created rule {} with pending generation", rule.getId());

        return rule.getId();
    }
}

// Outbox processor
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxProcessor {

    private final OutboxRepository outboxRepository;
    private final RecurringRuleGenerationService generationService;
    private final RecurringRuleRepository ruleRepository;

    /**
     * Process pending outbox events.
     * Runs every 5 seconds.
     */
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.SECONDS)
    public void processPendingEvents() {
        List<OutboxEvent> pending = outboxRepository
            .findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        for (OutboxEvent event : pending) {
            processEvent(event);
        }
    }

    private void processEvent(OutboxEvent event) {
        try {
            event.setStatus(OutboxStatus.PROCESSING);
            event.setAttemptCount(event.getAttemptCount() + 1);
            outboxRepository.save(event);

            switch (event.getEventType()) {
                case "GENERATE_TRANSACTIONS" -> processGeneration(event);
                case "DELETE_TRANSACTIONS" -> processDeletion(event);
                default -> log.warn("Unknown event type: {}", event.getEventType());
            }

            event.setStatus(OutboxStatus.COMPLETED);
            event.setProcessedAt(ZonedDateTime.now());
            outboxRepository.save(event);

        } catch (Exception e) {
            log.error("Failed to process outbox event {}: {}", event.getId(), e.getMessage());

            event.setLastError(e.getMessage());

            if (event.getAttemptCount() >= MAX_ATTEMPTS) {
                event.setStatus(OutboxStatus.FAILED);
                // Also update rule status
                markRuleAsFailed(event.getAggregateId(), e.getMessage());
            } else {
                event.setStatus(OutboxStatus.PENDING);  // Retry
            }

            outboxRepository.save(event);
        }
    }

    private void processGeneration(OutboxEvent event) {
        GenerateTransactionsPayload payload = fromJson(event.getPayload());

        RecurringRule rule = ruleRepository.findById(payload.ruleId())
            .orElseThrow();

        generationService.generateForRule(rule, payload.horizon());
    }

    private static final int MAX_ATTEMPTS = 5;
}
```

### 6.3 Diagram: Outbox Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         OUTBOX PATTERN FLOW                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. USER: Create Rule                                                        │
│     │                                                                        │
│     ▼                                                                        │
│  2. BEGIN TRANSACTION                                                        │
│     ├─ Save RecurringRule (status=PENDING_GENERATION)                       │
│     └─ Save OutboxEvent (type=GENERATE_TRANSACTIONS)                        │
│     │                                                                        │
│     ▼                                                                        │
│  3. COMMIT TRANSACTION                                                       │
│     │                                                                        │
│     │ ← At this point, user gets response                                   │
│     │   "Rule created, generating transactions..."                          │
│     │                                                                        │
│     ▼                                                                        │
│  4. OUTBOX PROCESSOR (async, every 5s)                                       │
│     │                                                                        │
│     ├─ Read pending OutboxEvent                                              │
│     ├─ Call CashFlow HTTP API                                                │
│     │   ├─ Success → mark event COMPLETED, rule ACTIVE                      │
│     │   └─ Failure → retry or mark FAILED                                   │
│     │                                                                        │
│     ▼                                                                        │
│  5. FINAL STATE                                                              │
│     ├─ Rule: status=ACTIVE, generationStatus=COMPLETED                      │
│     └─ Transactions: created in CashFlow                                     │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  FAILURE SCENARIOS:                                                          │
│                                                                              │
│  A. Crash after transaction commit, before outbox processing:               │
│     → Outbox event survives, will be processed on restart                   │
│                                                                              │
│  B. HTTP call fails:                                                         │
│     → Outbox event stays PENDING, retried later                             │
│                                                                              │
│  C. Max retries exceeded:                                                    │
│     → Outbox event marked FAILED                                            │
│     → Rule marked with generationStatus=FAILED                              │
│     → User notified                                                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Monitoring & Alerting

### 7.1 Metrics

```java
package com.multi.vidulum.recurring_rules.infrastructure;

@Component
@RequiredArgsConstructor
public class RecurringRulesMetrics {

    private final MeterRegistry registry;

    // Counters
    private Counter rulesCreated;
    private Counter rulesPaused;
    private Counter rulesDeleted;
    private Counter generationsSucceeded;
    private Counter generationsFailed;
    private Counter httpCallsTotal;
    private Counter httpCallsFailed;

    // Gauges
    private AtomicInteger activeRulesCount;
    private AtomicInteger pausedRulesCount;
    private AtomicInteger failedGenerationsCount;
    private AtomicInteger pendingOutboxEvents;

    // Timers
    private Timer generationDuration;
    private Timer httpCallDuration;

    @PostConstruct
    void init() {
        rulesCreated = Counter.builder("recurring_rules.created")
            .description("Number of rules created")
            .register(registry);

        generationsFailed = Counter.builder("recurring_rules.generation.failed")
            .description("Number of failed generations")
            .register(registry);

        activeRulesCount = registry.gauge("recurring_rules.active",
            new AtomicInteger(0));

        failedGenerationsCount = registry.gauge("recurring_rules.generation.failed.total",
            new AtomicInteger(0));

        generationDuration = Timer.builder("recurring_rules.generation.duration")
            .description("Time to generate transactions")
            .register(registry);

        httpCallDuration = Timer.builder("recurring_rules.http.duration")
            .description("HTTP call duration to CashFlow API")
            .tag("endpoint", "batch_create")
            .register(registry);
    }

    public void recordRuleCreated() {
        rulesCreated.increment();
    }

    public void recordGenerationSuccess(Duration duration) {
        generationsSucceeded.increment();
        generationDuration.record(duration);
    }

    public void recordGenerationFailure(String reason) {
        generationsFailed.increment();
        Counter.builder("recurring_rules.generation.failed.by_reason")
            .tag("reason", reason)
            .register(registry)
            .increment();
    }

    public void recordHttpCall(Duration duration, boolean success, int statusCode) {
        httpCallsTotal.increment();
        httpCallDuration.record(duration);

        if (!success) {
            httpCallsFailed.increment();
            Counter.builder("recurring_rules.http.failed.by_status")
                .tag("status", String.valueOf(statusCode))
                .register(registry)
                .increment();
        }
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    public void updateGauges() {
        activeRulesCount.set(ruleRepository.countByStatus(RuleStatus.ACTIVE));
        pausedRulesCount.set(ruleRepository.countByStatus(RuleStatus.PAUSED));
        failedGenerationsCount.set(ruleRepository.countByGenerationStatus(GenerationStatus.FAILED));
        pendingOutboxEvents.set(outboxRepository.countByStatus(OutboxStatus.PENDING));
    }
}
```

### 7.2 Health Indicators

```java
@Component
public class RecurringRulesHealthIndicator implements HealthIndicator {

    private final RecurringRuleRepository ruleRepository;
    private final OutboxRepository outboxRepository;
    private final CashFlowServiceClient cashFlowClient;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // Check failed rules
        long failedRules = ruleRepository.countByGenerationStatus(GenerationStatus.FAILED);
        if (failedRules > 10) {
            builder.status(Status.DOWN)
                .withDetail("failedRules", failedRules)
                .withDetail("reason", "Too many failed rules");
        } else if (failedRules > 0) {
            builder.status(new Status("DEGRADED"))
                .withDetail("failedRules", failedRules);
        }

        // Check outbox backlog
        long pendingEvents = outboxRepository.countByStatus(OutboxStatus.PENDING);
        long stuckEvents = outboxRepository.countByStatusAndCreatedAtBefore(
            OutboxStatus.PENDING,
            ZonedDateTime.now().minusHours(1)
        );

        if (stuckEvents > 0) {
            builder.status(Status.DOWN)
                .withDetail("stuckOutboxEvents", stuckEvents)
                .withDetail("reason", "Outbox events stuck for >1 hour");
        }

        builder.withDetail("pendingOutboxEvents", pendingEvents);

        // Check CashFlow API connectivity
        try {
            cashFlowClient.healthCheck();
            builder.withDetail("cashFlowApi", "UP");
        } catch (Exception e) {
            builder.status(new Status("DEGRADED"))
                .withDetail("cashFlowApi", "DOWN")
                .withDetail("cashFlowApiError", e.getMessage());
        }

        return builder.build();
    }
}
```

### 7.3 Alerting Rules (Prometheus/Grafana)

```yaml
# prometheus-alerts.yml

groups:
  - name: recurring_rules
    rules:
      # Too many failed generations
      - alert: RecurringRulesGenerationFailureHigh
        expr: increase(recurring_rules_generation_failed_total[1h]) > 10
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High number of recurring rule generation failures"
          description: "{{ $value }} generation failures in the last hour"

      # Outbox backlog growing
      - alert: RecurringRulesOutboxBacklog
        expr: recurring_rules_outbox_pending > 100
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Recurring rules outbox backlog growing"
          description: "{{ $value }} pending outbox events"

      # Stuck outbox events
      - alert: RecurringRulesOutboxStuck
        expr: recurring_rules_outbox_stuck > 0
        for: 30m
        labels:
          severity: critical
        annotations:
          summary: "Stuck outbox events detected"
          description: "{{ $value }} events stuck for >1 hour"

      # CashFlow API down
      - alert: RecurringRulesCashFlowApiDown
        expr: recurring_rules_http_failed_total / recurring_rules_http_total > 0.5
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "CashFlow API failure rate >50%"
          description: "Recurring rules cannot communicate with CashFlow"

      # Many paused rules
      - alert: RecurringRulesManyPaused
        expr: recurring_rules_paused > recurring_rules_active * 0.5
        for: 1h
        labels:
          severity: warning
        annotations:
          summary: "Many recurring rules are paused"
          description: "{{ $value }} paused vs {{ $labels.active }} active"
```

---

## 8. Database Schema Changes

### 8.1 RecurringRule Collection

```javascript
// MongoDB schema for recurring_rules collection

{
  "_id": "RR10000001",
  "cashFlowId": "CF10000001",
  "userId": "U10000001",

  // Basic info
  "name": "Czynsz",
  "description": "Miesięczny czynsz",
  "amount": { "amount": 2000.00, "currency": "PLN" },
  "amountIsEstimate": false,
  "type": "OUTFLOW",
  "categoryName": "Mieszkanie",

  // Pattern
  "pattern": {
    "_type": "Monthly",
    "dayOfMonth": 10,
    "interval": 1
  },

  // Validity
  "startDate": ISODate("2026-03-01"),
  "endDate": null,
  "maxOccurrences": null,
  "activeMonths": [],
  "excludedDates": [],

  // Matching hints
  "counterpartyName": null,
  "counterpartyAccount": null,
  "amountTolerance": null,
  "dateTolerance": 5,

  // Status
  "status": "ACTIVE",  // ACTIVE, PAUSED, ENDED
  "pauseReason": null,  // NEW: MANUAL, CATEGORY_ARCHIVED, CASHFLOW_CLOSED, GENERATION_FAILED
  "endReason": null,    // MANUAL, MAX_OCCURRENCES, END_DATE, CASHFLOW_DELETED

  // Generation tracking - NEW
  "generationStatus": "COMPLETED",  // IDLE, PENDING, IN_PROGRESS, COMPLETED, FAILED
  "lastGenerationError": null,
  "lastGenerationAttempt": ISODate("2026-02-25T10:00:00Z"),
  "consecutiveFailures": 0,
  "pendingGenerationUpTo": null,

  // Existing tracking
  "generatedCount": 12,
  "lastGeneratedPeriod": "2027-02",

  // Timestamps
  "createdAt": ISODate("2026-02-24T10:00:00Z"),
  "lastModifiedAt": ISODate("2026-02-24T10:00:00Z"),
  "pausedAt": null,
  "endedAt": null,

  // Orphan tracking - NEW
  "orphaned": false,
  "orphanedAt": null,

  // Metadata
  "notes": "",
  "version": 1
}

// Indexes
db.recurring_rules.createIndex({ "ruleId": 1 }, { unique: true })
db.recurring_rules.createIndex({ "cashFlowId": 1, "status": 1 })
db.recurring_rules.createIndex({ "cashFlowId": 1, "categoryName": 1 })
db.recurring_rules.createIndex({ "status": 1, "generationStatus": 1 })
db.recurring_rules.createIndex({ "orphaned": 1, "orphanedAt": 1 })
db.recurring_rules.createIndex({ "userId": 1 })
```

### 8.2 Outbox Collection

```javascript
// MongoDB schema for recurring_rules_outbox collection

{
  "_id": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "GENERATE_TRANSACTIONS",
  "aggregateId": "RR10000001",
  "payload": "{\"ruleId\":\"RR10000001\",\"cashFlowId\":\"CF10000001\",\"horizon\":\"2027-02\"}",
  "createdAt": ISODate("2026-02-25T10:00:00Z"),
  "processedAt": null,
  "attemptCount": 0,
  "lastError": null,
  "status": "PENDING"  // PENDING, PROCESSING, COMPLETED, FAILED
}

// Indexes
db.recurring_rules_outbox.createIndex({ "status": 1, "createdAt": 1 })
db.recurring_rules_outbox.createIndex({ "status": 1, "createdAt": 1 },
  { partialFilterExpression: { status: "PENDING" } })
```

### 8.3 CashChange Extension (w CashFlow)

```javascript
// Rozszerzenie CashChange w cashflow collection

{
  // ... existing CashChange fields

  // NEW: Link to recurring rule
  "sourceRuleId": "RR10000001",  // null for manual transactions
  "generatedAt": ISODate("2026-02-25T10:00:00Z")  // when generated
}

// Index for finding transactions by rule
db.cash_flows.createIndex({
  "cashChanges.sourceRuleId": 1,
  "cashChanges.dueDate": 1
})

// Unique constraint for idempotency
db.cash_flows.createIndex(
  {
    "_id": 1,
    "cashChanges.sourceRuleId": 1,
    "cashChanges.dueDate": 1
  },
  {
    unique: true,
    partialFilterExpression: {
      "cashChanges.sourceRuleId": { $ne: null }
    }
  }
)
```

---

## 9. API Contracts

### 9.1 Error Response Format

```java
public record ApiError(
    String errorCode,
    String message,
    Map<String, Object> details,
    ZonedDateTime timestamp,
    String traceId
) {}

// Error codes for recurring_rules
public enum RecurringRulesErrorCode {
    // Validation errors (4xx)
    RULE_VALIDATION_ERROR("RR001", "Rule validation failed"),
    CATEGORY_NOT_FOUND("RR002", "Category does not exist"),
    CATEGORY_ARCHIVED("RR003", "Category is archived"),
    CATEGORY_TYPE_MISMATCH("RR004", "Category type does not match rule type"),
    RULE_NOT_FOUND("RR005", "Rule not found"),
    RULE_ALREADY_ENDED("RR006", "Cannot modify ended rule"),
    INVALID_PATTERN("RR007", "Invalid recurrence pattern"),
    DUPLICATE_RULE("RR008", "Similar rule already exists"),

    // Generation errors
    GENERATION_FAILED("RR101", "Failed to generate transactions"),
    CASHFLOW_API_ERROR("RR102", "CashFlow service error"),
    CASHFLOW_NOT_FOUND("RR103", "CashFlow not found"),
    CASHFLOW_CLOSED("RR104", "CashFlow is closed"),

    // System errors (5xx)
    INTERNAL_ERROR("RR500", "Internal server error"),
    SERVICE_UNAVAILABLE("RR503", "Service temporarily unavailable");

    private final String code;
    private final String defaultMessage;
}
```

### 9.2 Webhook Notifications (opcjonalnie)

```java
// Webhook payload for rule events
public record RuleNotification(
    String eventType,
    String ruleId,
    String ruleName,
    String cashFlowId,
    Map<String, Object> data,
    ZonedDateTime timestamp
) {}

// Event types
public enum NotificationEventType {
    RULE_CREATED,
    RULE_UPDATED,
    RULE_PAUSED,
    RULE_RESUMED,
    RULE_ENDED,
    RULE_DELETED,
    GENERATION_FAILED,
    RULE_AUTO_PAUSED,
    TRANSACTIONS_GENERATED
}
```

---

## 10. Implementation Order

### Phase 1: Core Foundation (Week 1-2)

```
□ 1.1 Create recurring_rules package structure
□ 1.2 Implement RecurringRule aggregate with new fields:
      - generationStatus
      - pauseReason
      - consecutiveFailures
      - orphaned/orphanedAt
□ 1.3 Implement ResilientCashFlowHttpClient with retry
□ 1.4 Implement CategoryValidationService
□ 1.5 Add basic error handling
□ 1.6 Create MongoDB indexes
```

### Phase 2: Event Handling (Week 2-3)

```
□ 2.1 Add CashFlow lifecycle events (if not exist):
      - CashFlowClosedEvent
      - CashFlowDeletedEvent
      - CategoryArchivedEvent
      - CategoryUnarchivedEvent
□ 2.2 Implement CashFlowLifecycleEventHandler
□ 2.3 Implement CategoryArchivedEventHandler
□ 2.4 Implement auto-pause logic
```

### Phase 3: Reliability (Week 3-4)

```
□ 3.1 Implement Outbox pattern:
      - OutboxEvent entity
      - OutboxProcessor
      - TransactionalRuleService
□ 3.2 Implement FailedGenerationRecoveryService
□ 3.3 Add idempotency (unique constraint + handling)
□ 3.4 Implement OrphanDetectionService
```

### Phase 4: Observability (Week 4)

```
□ 4.1 Implement RecurringRulesMetrics
□ 4.2 Implement RecurringRulesHealthIndicator
□ 4.3 Configure alerting rules
□ 4.4 Add structured logging
```

### Phase 5: Testing (Week 5)

```
□ 5.1 Unit tests for all services
□ 5.2 Integration tests with Testcontainers
□ 5.3 Chaos testing (fail HTTP calls, kill services)
□ 5.4 Load testing (many rules, concurrent operations)
```

---

## Appendix: Code Snippets Summary

### Key Classes to Create

| Class | Package | Purpose |
|-------|---------|---------|
| `ResilientCashFlowHttpClient` | infrastructure | HTTP client with retry & circuit breaker |
| `CategoryValidationService` | app | Validate category before operations |
| `CategoryArchivedEventHandler` | app.eventhandlers | Handle category archive events |
| `CashFlowLifecycleEventHandler` | app.eventhandlers | Handle CashFlow close/delete |
| `FailedGenerationRecoveryService` | app | Retry failed generations |
| `OrphanDetectionService` | app | Detect and cleanup orphaned data |
| `TransactionalRuleService` | app | Outbox-based rule creation |
| `OutboxProcessor` | infrastructure | Process outbox events |
| `RecurringRulesMetrics` | infrastructure | Prometheus metrics |
| `RecurringRulesHealthIndicator` | infrastructure | Health checks |

### Key Enums to Add

```java
enum PauseReason { MANUAL, CATEGORY_ARCHIVED, CASHFLOW_CLOSED, GENERATION_FAILED }
enum GenerationStatus { IDLE, PENDING, IN_PROGRESS, COMPLETED, FAILED }
enum EndReason { MANUAL, MAX_OCCURRENCES, END_DATE, CASHFLOW_DELETED }
```

### Key Events to Handle

| Event | Source | Action in recurring_rules |
|-------|--------|---------------------------|
| `MonthRolledOverEvent` | cashflow | Generate next month |
| `CategoryArchivedEvent` | cashflow | Pause affected rules |
| `CategoryUnarchivedEvent` | cashflow | Notify user |
| `CashFlowClosedEvent` | cashflow | Pause all rules |
| `CashFlowDeletedEvent` | cashflow | Delete all rules |
