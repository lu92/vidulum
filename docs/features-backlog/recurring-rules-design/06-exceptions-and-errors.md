# Exceptions and Errors - Recurring Rules

**Powiązane:** [05-bounded-context-integration.md](./05-bounded-context-integration.md) | [Następny: 07-test-design.md](./07-test-design.md)

---

## 1. Katalog kodów błędów

### 1.1 Struktura kodów

```
RR = Recurring Rules prefix

RR0XX = Validation errors (input validation)
RR1XX = Rule lifecycle errors
RR2XX = Amount change errors
RR3XX = Execution errors
RR4XX = Integration errors (CashFlow communication)
RR5XX = Infrastructure errors
```

### 1.2 Pełna lista kodów

| Code | HTTP Status | Nazwa | Opis | Przykład |
|------|-------------|-------|------|----------|
| **RR001** | 400 | `VALIDATION_ERROR` | Bean validation failed | Missing required field |
| **RR002** | 400 | `INVALID_RECURRENCE_PATTERN` | Invalid pattern configuration | dayOfMonth > 31 |
| **RR003** | 404 | `CASHFLOW_NOT_FOUND` | CashFlow doesn't exist | Invalid cashFlowId |
| **RR004** | 400 | `CATEGORY_NOT_FOUND` | Category not in CashFlow | Non-existent category |
| **RR005** | 400 | `CATEGORY_ARCHIVED` | Category is archived | Cannot use archived |
| **RR006** | 400 | `CATEGORY_TYPE_MISMATCH` | Category type ≠ rule type | OUTFLOW cat for INFLOW rule |
| **RR007** | 400 | `CASHFLOW_NOT_OPEN` | CashFlow not in OPEN status | CashFlow is CLOSED |
| **RR008** | 400 | `CURRENCY_MISMATCH` | Currency doesn't match CashFlow | PLN rule for EUR CashFlow |
| **RR009** | 400 | `INVALID_DATE_RANGE` | endDate ≤ startDate | End before start |
| **RR010** | 400 | `INVALID_DAY_OF_MONTH` | dayOfMonth > 28 without adjust | Feb 30 without flag |
| **RR101** | 404 | `RULE_NOT_FOUND` | Rule doesn't exist | Invalid ruleId |
| **RR102** | 400 | `RULE_ALREADY_DELETED` | Cannot modify deleted rule | Update deleted |
| **RR103** | 409 | `INVALID_RULE_STATUS` | Operation not allowed in status | Pause already paused |
| **RR104** | 409 | `CONCURRENT_MODIFICATION` | Optimistic lock failure | Race condition |
| **RR105** | 400 | `RULE_ALREADY_COMPLETED` | Rule has ended | Modify completed |
| **RR106** | 409 | `DUPLICATE_RULE` | Idempotency key conflict | Duplicate create |
| **RR201** | 404 | `AMOUNT_CHANGE_NOT_FOUND` | Amount change doesn't exist | Invalid changeId |
| **RR202** | 400 | `AMOUNT_CHANGE_DATE_CONFLICT` | Date conflicts with existing | Same date ONE_TIME |
| **RR203** | 400 | `AMOUNT_CHANGE_INVALID_DATE` | Date outside rule range | Before startDate |
| **RR204** | 400 | `AMOUNT_CHANGE_CURRENCY_MISMATCH` | Currency ≠ rule currency | Different currency |
| **RR301** | 500 | `EXECUTION_FAILED` | Transaction generation failed | CashFlow error |
| **RR302** | 400 | `ALREADY_EXECUTED` | Already executed for date | Duplicate execution |
| **RR303** | 500 | `EXECUTION_TIMEOUT` | Execution timed out | Slow CashFlow |
| **RR401** | 503 | `CASHFLOW_SERVICE_UNAVAILABLE` | CashFlow service down | 5xx from CashFlow |
| **RR402** | 503 | `CASHFLOW_CIRCUIT_OPEN` | Circuit breaker is open | Too many failures |
| **RR403** | 504 | `CASHFLOW_TIMEOUT` | CashFlow call timed out | Slow response |
| **RR501** | 500 | `DATABASE_ERROR` | MongoDB operation failed | Connection lost |
| **RR502** | 500 | `KAFKA_ERROR` | Kafka operation failed | Broker down |
| **RR503** | 503 | `SERVICE_UNAVAILABLE` | General service unavailable | Maintenance |

---

## 2. Exception Hierarchy

### 2.1 Diagram klas

```
RuntimeException
    │
    └── RecurringRuleException (abstract)
            │
            ├── ValidationException
            │       ├── InvalidRecurrencePatternException
            │       ├── InvalidDateRangeException
            │       └── CurrencyMismatchException
            │
            ├── RuleNotFoundException
            │
            ├── RuleLifecycleException
            │       ├── RuleAlreadyDeletedException
            │       ├── RuleAlreadyCompletedException
            │       └── InvalidRuleStatusException
            │
            ├── ConcurrentModificationException
            │
            ├── AmountChangeException
            │       ├── AmountChangeNotFoundException
            │       ├── AmountChangeDateConflictException
            │       └── AmountChangeInvalidDateException
            │
            ├── CategoryValidationException
            │       ├── CategoryNotFoundException
            │       ├── CategoryArchivedException
            │       └── CategoryTypeMismatchException
            │
            ├── CashFlowIntegrationException
            │       ├── CashFlowNotFoundException
            │       ├── CashFlowNotOpenException
            │       ├── CashFlowServiceUnavailableException
            │       └── CashFlowTimeoutException
            │
            └── ExecutionException
                    ├── ExecutionFailedException
                    ├── AlreadyExecutedException
                    └── ExecutionTimeoutException
```

### 2.2 Base Exception

```java
package com.multi.vidulum.recurring_rules.domain.exception;

import lombok.Getter;

@Getter
public abstract class RecurringRuleException extends RuntimeException {

    private final String errorCode;
    private final ErrorCategory category;

    protected RecurringRuleException(String errorCode, String message, ErrorCategory category) {
        super(message);
        this.errorCode = errorCode;
        this.category = category;
    }

    protected RecurringRuleException(String errorCode, String message, ErrorCategory category, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.category = category;
    }

    public enum ErrorCategory {
        VALIDATION,
        NOT_FOUND,
        LIFECYCLE,
        CONFLICT,
        INTEGRATION,
        INFRASTRUCTURE
    }
}
```

### 2.3 Specific Exceptions

```java
// === VALIDATION ===

public class ValidationException extends RecurringRuleException {
    private final List<FieldError> fieldErrors;

    public ValidationException(List<FieldError> fieldErrors) {
        super("RR001", "Validation failed", ErrorCategory.VALIDATION);
        this.fieldErrors = fieldErrors;
    }

    public record FieldError(String field, String message) {}
}

public class InvalidRecurrencePatternException extends RecurringRuleException {
    public InvalidRecurrencePatternException(String details) {
        super("RR002", "Invalid recurrence pattern: " + details, ErrorCategory.VALIDATION);
    }
}

public class InvalidDateRangeException extends RecurringRuleException {
    public InvalidDateRangeException(LocalDate startDate, LocalDate endDate) {
        super("RR009",
              String.format("End date %s must be after start date %s", endDate, startDate),
              ErrorCategory.VALIDATION);
    }
}

public class CurrencyMismatchException extends RecurringRuleException {
    public CurrencyMismatchException(String expected, String actual) {
        super("RR008",
              String.format("Currency mismatch: expected %s, got %s", expected, actual),
              ErrorCategory.VALIDATION);
    }
}

// === NOT FOUND ===

public class RuleNotFoundException extends RecurringRuleException {
    private final RecurringRuleId ruleId;

    public RuleNotFoundException(RecurringRuleId ruleId) {
        super("RR101",
              String.format("Recurring rule '%s' not found", ruleId.id()),
              ErrorCategory.NOT_FOUND);
        this.ruleId = ruleId;
    }
}

public class AmountChangeNotFoundException extends RecurringRuleException {
    public AmountChangeNotFoundException(AmountChangeId changeId) {
        super("RR201",
              String.format("Amount change '%s' not found", changeId.id()),
              ErrorCategory.NOT_FOUND);
    }
}

// === LIFECYCLE ===

public class RuleAlreadyDeletedException extends RecurringRuleException {
    public RuleAlreadyDeletedException(RecurringRuleId ruleId) {
        super("RR102",
              String.format("Cannot modify deleted rule '%s'", ruleId.id()),
              ErrorCategory.LIFECYCLE);
    }
}

public class InvalidRuleStatusException extends RecurringRuleException {
    public InvalidRuleStatusException(RecurringRuleId ruleId, RuleStatus current, RuleStatus required) {
        super("RR103",
              String.format("Rule '%s' is in status %s, but %s is required",
                      ruleId.id(), current, required),
              ErrorCategory.LIFECYCLE);
    }

    public InvalidRuleStatusException(RecurringRuleId ruleId, RuleStatus current, String operation) {
        super("RR103",
              String.format("Cannot %s rule '%s' in status %s",
                      operation, ruleId.id(), current),
              ErrorCategory.LIFECYCLE);
    }
}

// === CONFLICT ===

public class ConcurrentModificationException extends RecurringRuleException {
    public ConcurrentModificationException(RecurringRuleId ruleId) {
        super("RR104",
              String.format("Rule '%s' was modified by another request. Please retry.",
                      ruleId.id()),
              ErrorCategory.CONFLICT);
    }
}

public class AmountChangeDateConflictException extends RecurringRuleException {
    public AmountChangeDateConflictException(LocalDate date) {
        super("RR202",
              String.format("An amount change already exists for date %s", date),
              ErrorCategory.CONFLICT);
    }
}

// === CATEGORY VALIDATION ===

public class CategoryNotFoundException extends RecurringRuleException {
    public CategoryNotFoundException(String categoryName, CashFlowId cashFlowId) {
        super("RR004",
              String.format("Category '%s' not found in CashFlow '%s'",
                      categoryName, cashFlowId.id()),
              ErrorCategory.VALIDATION);
    }
}

public class CategoryArchivedException extends RecurringRuleException {
    public CategoryArchivedException(String categoryName) {
        super("RR005",
              String.format("Category '%s' is archived and cannot be used", categoryName),
              ErrorCategory.VALIDATION);
    }
}

public class CategoryTypeMismatchException extends RecurringRuleException {
    public CategoryTypeMismatchException(String categoryName, Type ruleType, Type categoryType) {
        super("RR006",
              String.format("Category '%s' is of type %s, but rule requires %s",
                      categoryName, categoryType, ruleType),
              ErrorCategory.VALIDATION);
    }
}

// === CASHFLOW INTEGRATION ===

public class CashFlowNotFoundException extends RecurringRuleException {
    public CashFlowNotFoundException(CashFlowId cashFlowId) {
        super("RR003",
              String.format("CashFlow '%s' not found", cashFlowId.id()),
              ErrorCategory.NOT_FOUND);
    }
}

public class CashFlowNotOpenException extends RecurringRuleException {
    public CashFlowNotOpenException(CashFlowId cashFlowId, String status) {
        super("RR007",
              String.format("CashFlow '%s' is in status %s. Only OPEN CashFlows can have recurring rules.",
                      cashFlowId.id(), status),
              ErrorCategory.VALIDATION);
    }
}

public class CashFlowServiceUnavailableException extends RecurringRuleException {
    private final Integer retryAfterSeconds;

    public CashFlowServiceUnavailableException(String message) {
        super("RR401", message, ErrorCategory.INTEGRATION);
        this.retryAfterSeconds = 30;
    }

    public CashFlowServiceUnavailableException(String message, int retryAfterSeconds) {
        super("RR401", message, ErrorCategory.INTEGRATION);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}

public class CashFlowCircuitOpenException extends RecurringRuleException {
    public CashFlowCircuitOpenException() {
        super("RR402",
              "CashFlow service circuit breaker is open due to repeated failures. Please try again later.",
              ErrorCategory.INTEGRATION);
    }
}

public class CashFlowTimeoutException extends RecurringRuleException {
    public CashFlowTimeoutException(Duration timeout) {
        super("RR403",
              String.format("CashFlow service did not respond within %d seconds", timeout.toSeconds()),
              ErrorCategory.INTEGRATION);
    }
}

// === EXECUTION ===

public class ExecutionFailedException extends RecurringRuleException {
    public ExecutionFailedException(RecurringRuleId ruleId, LocalDate date, String reason) {
        super("RR301",
              String.format("Failed to execute rule '%s' for date %s: %s",
                      ruleId.id(), date, reason),
              ErrorCategory.INFRASTRUCTURE);
    }
}

public class AlreadyExecutedException extends RecurringRuleException {
    public AlreadyExecutedException(RecurringRuleId ruleId, LocalDate date) {
        super("RR302",
              String.format("Rule '%s' has already been executed for date %s",
                      ruleId.id(), date),
              ErrorCategory.CONFLICT);
    }
}
```

---

## 3. Error Response Format

### 3.1 Standard Error Response

```java
public record ErrorResponse(
    ZonedDateTime timestamp,
    int status,
    String error,       // HTTP status phrase
    String code,        // Business error code (RR001, etc.)
    String message,     // Human-readable message
    String path,        // Request path
    String requestId,   // Correlation ID
    List<FieldErrorDto> fieldErrors,  // For validation errors
    Map<String, Object> details       // Additional context
) {
    public record FieldErrorDto(
        String field,
        String message,
        Object rejectedValue
    ) {}
}
```

### 3.2 Przykłady odpowiedzi

#### Validation Error (RR001)
```json
{
  "timestamp": "2026-02-25T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "RR001",
  "message": "Validation failed",
  "path": "/api/v1/recurring-rules",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "fieldErrors": [
    {
      "field": "name",
      "message": "Name is required",
      "rejectedValue": null
    },
    {
      "field": "amount.amount",
      "message": "Amount must be at least 0.01",
      "rejectedValue": 0
    },
    {
      "field": "recurrencePattern.dayOfMonth",
      "message": "Day of month must be between 1 and 31",
      "rejectedValue": 32
    }
  ],
  "details": null
}
```

#### Rule Not Found (RR101)
```json
{
  "timestamp": "2026-02-25T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "RR101",
  "message": "Recurring rule 'RR99999999' not found",
  "path": "/api/v1/recurring-rules/RR99999999",
  "requestId": "550e8400-e29b-41d4-a716-446655440001",
  "fieldErrors": null,
  "details": {
    "ruleId": "RR99999999"
  }
}
```

#### Category Archived (RR005)
```json
{
  "timestamp": "2026-02-25T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "RR005",
  "message": "Category 'OldCategory' is archived and cannot be used",
  "path": "/api/v1/recurring-rules",
  "requestId": "550e8400-e29b-41d4-a716-446655440002",
  "fieldErrors": null,
  "details": {
    "categoryName": "OldCategory",
    "archivedAt": "2026-02-20T15:00:00Z",
    "suggestion": "Please select an active category or unarchive this category first"
  }
}
```

#### Concurrent Modification (RR104)
```json
{
  "timestamp": "2026-02-25T10:30:00Z",
  "status": 409,
  "error": "Conflict",
  "code": "RR104",
  "message": "Rule 'RR10000001' was modified by another request. Please retry.",
  "path": "/api/v1/recurring-rules/RR10000001",
  "requestId": "550e8400-e29b-41d4-a716-446655440003",
  "fieldErrors": null,
  "details": {
    "ruleId": "RR10000001",
    "yourVersion": 5,
    "currentVersion": 6,
    "suggestion": "Fetch the latest version and retry your changes"
  }
}
```

#### Service Unavailable (RR401)
```json
{
  "timestamp": "2026-02-25T10:30:00Z",
  "status": 503,
  "error": "Service Unavailable",
  "code": "RR401",
  "message": "CashFlow service is temporarily unavailable. Please try again later.",
  "path": "/api/v1/recurring-rules",
  "requestId": "550e8400-e29b-41d4-a716-446655440004",
  "fieldErrors": null,
  "details": {
    "service": "CashFlow",
    "retryAfter": 30,
    "circuitBreakerStatus": "HALF_OPEN"
  }
}
```

---

## 4. Global Exception Handler

```java
@RestControllerAdvice
@Slf4j
public class RecurringRulesExceptionHandler {

    @ExceptionHandler(RecurringRuleException.class)
    public ResponseEntity<ErrorResponse> handleRecurringRuleException(
            RecurringRuleException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = mapToHttpStatus(ex);
        String requestId = getOrGenerateRequestId(request);

        ErrorResponse response = new ErrorResponse(
                ZonedDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                ex.getErrorCode(),
                ex.getMessage(),
                request.getRequestURI(),
                requestId,
                extractFieldErrors(ex),
                extractDetails(ex)
        );

        logError(ex, requestId, status);

        return ResponseEntity
                .status(status)
                .header("X-Request-Id", requestId)
                .body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String requestId = getOrGenerateRequestId(request);

        List<ErrorResponse.FieldErrorDto> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new ErrorResponse.FieldErrorDto(
                        fe.getField(),
                        fe.getDefaultMessage(),
                        fe.getRejectedValue()
                ))
                .toList();

        ErrorResponse response = new ErrorResponse(
                ZonedDateTime.now(),
                400,
                "Bad Request",
                "RR001",
                "Validation failed",
                request.getRequestURI(),
                requestId,
                fieldErrors,
                null
        );

        log.warn("Validation error [{}]: {}", requestId, fieldErrors);

        return ResponseEntity.badRequest()
                .header("X-Request-Id", requestId)
                .body(response);
    }

    @ExceptionHandler(CashFlowServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(
            CashFlowServiceUnavailableException ex,
            HttpServletRequest request
    ) {
        String requestId = getOrGenerateRequestId(request);

        ErrorResponse response = new ErrorResponse(
                ZonedDateTime.now(),
                503,
                "Service Unavailable",
                ex.getErrorCode(),
                ex.getMessage(),
                request.getRequestURI(),
                requestId,
                null,
                Map.of(
                        "service", "CashFlow",
                        "retryAfter", ex.getRetryAfterSeconds()
                )
        );

        log.error("Service unavailable [{}]: {}", requestId, ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("X-Request-Id", requestId)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception ex,
            HttpServletRequest request
    ) {
        String requestId = getOrGenerateRequestId(request);

        log.error("Unexpected error [{}]: {}", requestId, ex.getMessage(), ex);

        ErrorResponse response = new ErrorResponse(
                ZonedDateTime.now(),
                500,
                "Internal Server Error",
                "RR500",
                "An unexpected error occurred. Please contact support with request ID: " + requestId,
                request.getRequestURI(),
                requestId,
                null,
                null
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Request-Id", requestId)
                .body(response);
    }

    private HttpStatus mapToHttpStatus(RecurringRuleException ex) {
        return switch (ex.getCategory()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case LIFECYCLE -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INTEGRATION -> {
                if (ex instanceof CashFlowTimeoutException) {
                    yield HttpStatus.GATEWAY_TIMEOUT;
                }
                yield HttpStatus.SERVICE_UNAVAILABLE;
            }
            case INFRASTRUCTURE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private String getOrGenerateRequestId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return requestId != null ? requestId : UUID.randomUUID().toString();
    }

    private List<ErrorResponse.FieldErrorDto> extractFieldErrors(RecurringRuleException ex) {
        if (ex instanceof ValidationException ve) {
            return ve.getFieldErrors().stream()
                    .map(fe -> new ErrorResponse.FieldErrorDto(fe.field(), fe.message(), null))
                    .toList();
        }
        return null;
    }

    private Map<String, Object> extractDetails(RecurringRuleException ex) {
        Map<String, Object> details = new HashMap<>();

        if (ex instanceof RuleNotFoundException rne) {
            details.put("ruleId", rne.getRuleId().id());
        } else if (ex instanceof ConcurrentModificationException cme) {
            details.put("suggestion", "Fetch the latest version and retry your changes");
        } else if (ex instanceof CategoryArchivedException) {
            details.put("suggestion", "Please select an active category or unarchive this category first");
        }

        return details.isEmpty() ? null : details;
    }

    private void logError(RecurringRuleException ex, String requestId, HttpStatus status) {
        if (status.is5xxServerError()) {
            log.error("Error [{}] {}: {}", requestId, ex.getErrorCode(), ex.getMessage(), ex);
        } else if (status == HttpStatus.NOT_FOUND) {
            log.debug("Not found [{}] {}: {}", requestId, ex.getErrorCode(), ex.getMessage());
        } else {
            log.warn("Client error [{}] {}: {}", requestId, ex.getErrorCode(), ex.getMessage());
        }
    }
}
```

---

## 5. Problem Details (RFC 7807)

Alternatywnie, można użyć standardu RFC 7807 Problem Details:

```java
@RestControllerAdvice
public class ProblemDetailsExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(RecurringRuleException.class)
    public ProblemDetail handleRecurringRuleException(RecurringRuleException ex) {
        HttpStatus status = mapToHttpStatus(ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("https://vidulum.com/errors/" + ex.getErrorCode()));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", ZonedDateTime.now());

        return problem;
    }
}
```

Przykład odpowiedzi:
```json
{
  "type": "https://vidulum.com/errors/RR004",
  "title": "Bad Request",
  "status": 400,
  "detail": "Category 'NonExistent' not found in CashFlow 'CF10000001'",
  "instance": "/api/v1/recurring-rules",
  "errorCode": "RR004",
  "timestamp": "2026-02-25T10:30:00Z"
}
```

---

## 6. Error Monitoring i Alerting

### 6.1 Metryki błędów

```java
@Component
@RequiredArgsConstructor
public class ErrorMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public void recordError(RecurringRuleException ex, String endpoint) {
        Counter.builder("recurring_rules.errors")
                .tag("error_code", ex.getErrorCode())
                .tag("category", ex.getCategory().name())
                .tag("endpoint", endpoint)
                .register(meterRegistry)
                .increment();
    }
}
```

### 6.2 Alerty

| Metryka | Próg | Alert |
|---------|------|-------|
| `recurring_rules.errors{category="INFRASTRUCTURE"}` | > 10/min | Critical |
| `recurring_rules.errors{code="RR401"}` | > 5/min | Warning (CashFlow issues) |
| `recurring_rules.errors{code="RR104"}` | > 20/min | Warning (Concurrency issues) |
| `recurring_rules.errors{category="VALIDATION"}` | > 100/min | Info (Potential API abuse) |

---

## Następny dokument

Przejdź do [07-test-design.md](./07-test-design.md) aby zobaczyć design testów.
