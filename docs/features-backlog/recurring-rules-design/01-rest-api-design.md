# REST API Design - Recurring Rules

**Powiązane:** [00-overview.md](./00-overview.md) | [Następny: 02-domain-model.md](./02-domain-model.md)

---

## 1. Endpoints Overview

| Method | Endpoint | Opis | Auth |
|--------|----------|------|------|
| `POST` | `/api/v1/recurring-rules` | Utwórz nową regułę | Required |
| `GET` | `/api/v1/recurring-rules` | Lista reguł dla CashFlow | Required |
| `GET` | `/api/v1/recurring-rules/{ruleId}` | Pobierz szczegóły reguły | Required |
| `PUT` | `/api/v1/recurring-rules/{ruleId}` | Aktualizuj regułę | Required |
| `DELETE` | `/api/v1/recurring-rules/{ruleId}` | Usuń regułę | Required |
| `GET` | `/api/v1/recurring-rules/{ruleId}/impact-preview` | Podgląd wpływu usunięcia | Required |
| `POST` | `/api/v1/recurring-rules/{ruleId}/amount-changes` | Dodaj zmianę kwoty | Required |
| `DELETE` | `/api/v1/recurring-rules/{ruleId}/amount-changes/{changeId}` | Usuń zmianę kwoty | Required |
| `POST` | `/api/v1/recurring-rules/{ruleId}/pause` | Wstrzymaj regułę | Required |
| `POST` | `/api/v1/recurring-rules/{ruleId}/resume` | Wznów regułę | Required |
| `POST` | `/api/v1/recurring-rules/{ruleId}/generate` | Ręczne wygenerowanie (admin) | Admin |

---

## 2. Data Types i Enums

### 2.1 RecurrenceType
```java
public enum RecurrenceType {
    DAILY,      // Codziennie
    WEEKLY,     // Co tydzień (z określonym dniem tygodnia)
    MONTHLY,    // Co miesiąc (z określonym dniem miesiąca)
    YEARLY      // Co rok (z określoną datą)
}
```

### 2.2 RuleStatus
```java
public enum RuleStatus {
    ACTIVE,     // Reguła aktywna - generuje transakcje
    PAUSED,     // Wstrzymana - nie generuje, ale zachowana
    COMPLETED,  // Zakończona (endDate minął)
    DELETED     // Usunięta (soft delete)
}
```

### 2.3 AmountChangeType
```java
public enum AmountChangeType {
    ONE_TIME,   // Jednorazowa zmiana na konkretną datę
    PERMANENT   // Stała zmiana od danej daty
}
```

### 2.4 TransactionType
```java
public enum TransactionType {
    INFLOW,     // Przychód
    OUTFLOW     // Wydatek
}
```

---

## 3. Request/Response Schemas

### 3.1 Create Recurring Rule

#### Request: `POST /api/v1/recurring-rules`

```java
public record CreateRecurringRuleRequest(
    @NotBlank(message = "CashFlow ID is required")
    @Pattern(regexp = "CF\\d+", message = "Invalid CashFlow ID format")
    String cashFlowId,

    @NotBlank(message = "Name is required")
    @Size(min = 1, max = 100, message = "Name must be between 1 and 100 characters")
    String name,

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    String description,

    @NotNull(message = "Amount is required")
    @Valid
    MoneyDto amount,

    @NotNull(message = "Transaction type is required")
    TransactionType type,

    @NotBlank(message = "Category name is required")
    @Size(max = 100, message = "Category name cannot exceed 100 characters")
    String categoryName,

    @NotNull(message = "Recurrence pattern is required")
    @Valid
    RecurrencePatternDto recurrencePattern,

    @NotNull(message = "Start date is required")
    @FutureOrPresent(message = "Start date cannot be in the past")
    LocalDate startDate,

    @Future(message = "End date must be in the future")
    LocalDate endDate  // nullable - null means indefinite
) {
    // Custom validation
    @AssertTrue(message = "End date must be after start date")
    private boolean isEndDateValid() {
        return endDate == null || endDate.isAfter(startDate);
    }
}
```

#### MoneyDto
```java
public record MoneyDto(
    @NotNull(message = "Amount value is required")
    @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
    @DecimalMax(value = "999999999.99", message = "Amount cannot exceed 999,999,999.99")
    @Digits(integer = 9, fraction = 2, message = "Amount must have max 9 integer digits and 2 decimal places")
    BigDecimal amount,

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be 3 characters (ISO 4217)")
    @Pattern(regexp = "[A-Z]{3}", message = "Currency must be uppercase ISO 4217 code")
    String currency
) {}
```

#### RecurrencePatternDto (różne warianty)
```java
// Sealed interface dla różnych wzorców
public sealed interface RecurrencePatternDto {
    RecurrenceType type();
}

// DAILY
public record DailyPatternDto(
    @Min(value = 1, message = "Interval must be at least 1")
    @Max(value = 365, message = "Interval cannot exceed 365 days")
    int intervalDays  // co ile dni, default 1
) implements RecurrencePatternDto {
    @Override
    public RecurrenceType type() { return RecurrenceType.DAILY; }
}

// WEEKLY
public record WeeklyPatternDto(
    @NotNull(message = "Day of week is required")
    DayOfWeek dayOfWeek,

    @Min(value = 1, message = "Interval must be at least 1")
    @Max(value = 52, message = "Interval cannot exceed 52 weeks")
    int intervalWeeks  // co ile tygodni, default 1
) implements RecurrencePatternDto {
    @Override
    public RecurrenceType type() { return RecurrenceType.WEEKLY; }
}

// MONTHLY
public record MonthlyPatternDto(
    @Min(value = 1, message = "Day of month must be between 1 and 31")
    @Max(value = 31, message = "Day of month must be between 1 and 31")
    int dayOfMonth,

    @Min(value = 1, message = "Interval must be at least 1")
    @Max(value = 12, message = "Interval cannot exceed 12 months")
    int intervalMonths,  // co ile miesięcy, default 1

    boolean adjustForMonthEnd  // true = jeśli dayOfMonth > dni w miesiącu, użyj ostatniego dnia
) implements RecurrencePatternDto {
    @Override
    public RecurrenceType type() { return RecurrenceType.MONTHLY; }
}

// YEARLY
public record YearlyPatternDto(
    @Min(value = 1, message = "Month must be between 1 and 12")
    @Max(value = 12, message = "Month must be between 1 and 12")
    int month,

    @Min(value = 1, message = "Day must be between 1 and 31")
    @Max(value = 31, message = "Day must be between 1 and 31")
    int dayOfMonth
) implements RecurrencePatternDto {
    @Override
    public RecurrenceType type() { return RecurrenceType.YEARLY; }
}
```

#### Response: `201 Created`
```json
{
  "ruleId": "RR10000001",
  "cashFlowId": "CF10000001",
  "name": "Wynagrodzenie",
  "description": "Pensja miesięczna",
  "amount": {
    "amount": 8500.00,
    "currency": "PLN"
  },
  "type": "INFLOW",
  "categoryName": "Salary",
  "recurrencePattern": {
    "type": "MONTHLY",
    "dayOfMonth": 10,
    "intervalMonths": 1,
    "adjustForMonthEnd": false
  },
  "startDate": "2026-01-10",
  "endDate": null,
  "status": "ACTIVE",
  "nextOccurrence": "2026-03-10",
  "amountChanges": [],
  "createdAt": "2026-02-25T10:30:00Z",
  "lastModifiedAt": "2026-02-25T10:30:00Z"
}
```

#### Error Responses

| HTTP Status | Error Code | Opis |
|-------------|------------|------|
| 400 | `RR001` | Validation error (szczegóły w `fieldErrors`) |
| 400 | `RR002` | Invalid recurrence pattern |
| 404 | `RR003` | CashFlow not found |
| 400 | `RR004` | Category not found in CashFlow |
| 400 | `RR005` | Category archived |
| 400 | `RR006` | Category type mismatch (INFLOW category for OUTFLOW rule) |
| 503 | `RR503` | CashFlow service unavailable |

```json
// Example: Validation Error (400)
{
  "timestamp": "2026-02-25T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "RR001",
  "message": "Validation failed",
  "fieldErrors": [
    {
      "field": "name",
      "message": "Name is required"
    },
    {
      "field": "amount.amount",
      "message": "Amount must be at least 0.01"
    }
  ],
  "path": "/api/v1/recurring-rules"
}

// Example: Category Not Found (400)
{
  "timestamp": "2026-02-25T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "RR004",
  "message": "Category 'NonExistentCategory' not found in CashFlow 'CF10000001'",
  "path": "/api/v1/recurring-rules"
}

// Example: Service Unavailable (503)
{
  "timestamp": "2026-02-25T10:30:00Z",
  "status": 503,
  "error": "Service Unavailable",
  "code": "RR503",
  "message": "CashFlow service is temporarily unavailable. Please try again later.",
  "retryAfter": 30,
  "path": "/api/v1/recurring-rules"
}
```

---

### 3.2 List Recurring Rules

#### Request: `GET /api/v1/recurring-rules?cashFlowId={id}&status={status}&type={type}&page={page}&size={size}`

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `cashFlowId` | String | Yes | - | Filter by CashFlow |
| `status` | Enum | No | ACTIVE | Filter by status |
| `type` | Enum | No | - | Filter by INFLOW/OUTFLOW |
| `categoryName` | String | No | - | Filter by category |
| `page` | Integer | No | 0 | Page number (0-based) |
| `size` | Integer | No | 20 | Page size (max 100) |
| `sort` | String | No | createdAt,desc | Sort field and direction |

#### Response: `200 OK`
```json
{
  "content": [
    {
      "ruleId": "RR10000001",
      "cashFlowId": "CF10000001",
      "name": "Wynagrodzenie",
      "description": "Pensja miesięczna",
      "amount": {
        "amount": 8500.00,
        "currency": "PLN"
      },
      "type": "INFLOW",
      "categoryName": "Salary",
      "recurrencePattern": {
        "type": "MONTHLY",
        "dayOfMonth": 10,
        "intervalMonths": 1,
        "adjustForMonthEnd": false
      },
      "startDate": "2026-01-10",
      "endDate": null,
      "status": "ACTIVE",
      "nextOccurrence": "2026-03-10",
      "hasAmountChanges": true,
      "createdAt": "2026-02-25T10:30:00Z"
    },
    {
      "ruleId": "RR10000002",
      "cashFlowId": "CF10000001",
      "name": "Czynsz",
      "description": "Opłata za mieszkanie",
      "amount": {
        "amount": 2500.00,
        "currency": "PLN"
      },
      "type": "OUTFLOW",
      "categoryName": "Housing",
      "recurrencePattern": {
        "type": "MONTHLY",
        "dayOfMonth": 1,
        "intervalMonths": 1,
        "adjustForMonthEnd": false
      },
      "startDate": "2026-01-01",
      "endDate": null,
      "status": "ACTIVE",
      "nextOccurrence": "2026-03-01",
      "hasAmountChanges": false,
      "createdAt": "2026-02-25T10:35:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1,
  "hasNext": false,
  "hasPrevious": false
}
```

---

### 3.3 Get Recurring Rule Details

#### Request: `GET /api/v1/recurring-rules/{ruleId}`

#### Response: `200 OK`
```json
{
  "ruleId": "RR10000001",
  "cashFlowId": "CF10000001",
  "name": "Wynagrodzenie",
  "description": "Pensja miesięczna",
  "amount": {
    "amount": 8500.00,
    "currency": "PLN"
  },
  "type": "INFLOW",
  "categoryName": "Salary",
  "recurrencePattern": {
    "type": "MONTHLY",
    "dayOfMonth": 10,
    "intervalMonths": 1,
    "adjustForMonthEnd": false
  },
  "startDate": "2026-01-10",
  "endDate": null,
  "status": "ACTIVE",
  "nextOccurrence": "2026-03-10",
  "amountChanges": [
    {
      "changeId": "AC10000001",
      "effectiveDate": "2026-06-10",
      "type": "ONE_TIME",
      "newAmount": {
        "amount": 10000.00,
        "currency": "PLN"
      },
      "reason": "Premia roczna",
      "createdAt": "2026-02-25T11:00:00Z"
    },
    {
      "changeId": "AC10000002",
      "effectiveDate": "2026-07-01",
      "type": "PERMANENT",
      "newAmount": {
        "amount": 9000.00,
        "currency": "PLN"
      },
      "reason": "Podwyżka",
      "createdAt": "2026-02-25T11:05:00Z"
    }
  ],
  "executionHistory": [
    {
      "executionId": "EX10000001",
      "scheduledDate": "2026-02-10",
      "executedAt": "2026-02-10T06:00:00Z",
      "status": "SUCCESS",
      "generatedCashChangeId": "CC10000050"
    },
    {
      "executionId": "EX10000002",
      "scheduledDate": "2026-01-10",
      "executedAt": "2026-01-10T06:00:05Z",
      "status": "SUCCESS",
      "generatedCashChangeId": "CC10000020"
    }
  ],
  "statistics": {
    "totalExecutions": 2,
    "successfulExecutions": 2,
    "failedExecutions": 0,
    "totalGeneratedAmount": {
      "amount": 17000.00,
      "currency": "PLN"
    }
  },
  "createdAt": "2026-02-25T10:30:00Z",
  "lastModifiedAt": "2026-02-25T11:05:00Z"
}
```

#### Error Response: `404 Not Found`
```json
{
  "timestamp": "2026-02-25T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "RR101",
  "message": "Recurring rule 'RR99999999' not found",
  "path": "/api/v1/recurring-rules/RR99999999"
}
```

---

### 3.4 Update Recurring Rule

#### Request: `PUT /api/v1/recurring-rules/{ruleId}`

```java
public record UpdateRecurringRuleRequest(
    @Size(min = 1, max = 100, message = "Name must be between 1 and 100 characters")
    String name,  // null = no change

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    String description,  // null = no change

    @Valid
    MoneyDto amount,  // null = no change

    @Size(max = 100, message = "Category name cannot exceed 100 characters")
    String categoryName,  // null = no change

    @Valid
    RecurrencePatternDto recurrencePattern,  // null = no change

    LocalDate endDate,  // null = no change, empty string = remove end date

    @NotNull(message = "Apply to future only flag is required")
    boolean applyToFutureOnly  // true = tylko przyszłe, false = wszystkie niegenerowane
) {}
```

**Semantyka `applyToFutureOnly`:**
- `true` - Zmiany dotyczą tylko wystąpień od dzisiaj w przód
- `false` - Zmiany dotyczą wszystkich niegenerowanych wystąpień (włącznie z przeszłymi, które nie zostały jeszcze przetworzone)

#### Response: `200 OK`
```json
{
  "ruleId": "RR10000001",
  "affectedOccurrences": 12,
  "message": "Rule updated successfully. 12 future occurrences will be affected.",
  "rule": {
    // ... pełny obiekt RecurringRuleResponse
  }
}
```

#### Error Responses

| HTTP Status | Error Code | Opis |
|-------------|------------|------|
| 400 | `RR001` | Validation error |
| 400 | `RR004` | Category not found |
| 400 | `RR005` | Category archived |
| 400 | `RR006` | Category type mismatch |
| 400 | `RR102` | Cannot update deleted rule |
| 404 | `RR101` | Rule not found |
| 409 | `RR103` | Concurrent modification detected |

---

### 3.5 Delete Recurring Rule

#### Request: `DELETE /api/v1/recurring-rules/{ruleId}?deleteGeneratedTransactions={boolean}`

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `deleteGeneratedTransactions` | Boolean | No | false | Czy usunąć wygenerowane transakcje |

#### Response: `200 OK`
```json
{
  "ruleId": "RR10000001",
  "deletedAt": "2026-02-25T12:00:00Z",
  "affectedTransactions": {
    "total": 5,
    "deleted": 0,
    "preserved": 5
  },
  "message": "Rule deleted. 5 generated transactions were preserved."
}
```

#### Response (with deleteGeneratedTransactions=true): `200 OK`
```json
{
  "ruleId": "RR10000001",
  "deletedAt": "2026-02-25T12:00:00Z",
  "affectedTransactions": {
    "total": 5,
    "deleted": 3,
    "preserved": 2,
    "preservedReason": "2 transactions are already confirmed and cannot be deleted"
  },
  "message": "Rule deleted. 3 pending transactions were deleted, 2 confirmed transactions were preserved."
}
```

---

### 3.6 Preview Delete Impact

#### Request: `GET /api/v1/recurring-rules/{ruleId}/impact-preview`

#### Response: `200 OK`
```json
{
  "ruleId": "RR10000001",
  "ruleName": "Wynagrodzenie",
  "impact": {
    "futureOccurrences": {
      "count": 10,
      "totalAmount": {
        "amount": 85000.00,
        "currency": "PLN"
      },
      "dateRange": {
        "from": "2026-03-10",
        "to": "2026-12-10"
      }
    },
    "generatedTransactions": {
      "total": 2,
      "pending": 0,
      "confirmed": 2,
      "deletable": 0
    },
    "forecastImpact": {
      "affectedMonths": ["2026-03", "2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11", "2026-12"],
      "balanceReduction": {
        "amount": 85000.00,
        "currency": "PLN"
      }
    }
  },
  "warnings": [
    "This rule has generated 2 confirmed transactions that cannot be deleted",
    "Deleting this rule will remove 85,000.00 PLN from your 10-month forecast"
  ],
  "recommendations": [
    "Consider pausing the rule instead of deleting if you may need it again",
    "Set an end date to stop future occurrences while keeping the rule"
  ]
}
```

---

### 3.7 Add Amount Change

#### Request: `POST /api/v1/recurring-rules/{ruleId}/amount-changes`

```java
public record AddAmountChangeRequest(
    @NotNull(message = "Effective date is required")
    @FutureOrPresent(message = "Effective date cannot be in the past")
    LocalDate effectiveDate,

    @NotNull(message = "Change type is required")
    AmountChangeType type,

    @NotNull(message = "New amount is required")
    @Valid
    MoneyDto newAmount,

    @Size(max = 200, message = "Reason cannot exceed 200 characters")
    String reason
) {}
```

#### Response: `201 Created`
```json
{
  "changeId": "AC10000003",
  "ruleId": "RR10000001",
  "effectiveDate": "2026-04-10",
  "type": "PERMANENT",
  "newAmount": {
    "amount": 9500.00,
    "currency": "PLN"
  },
  "reason": "Kolejna podwyżka",
  "createdAt": "2026-02-25T14:00:00Z",
  "affectedOccurrences": 9,
  "message": "Amount change added. 9 future occurrences will use the new amount."
}
```

#### Error Responses

| HTTP Status | Error Code | Opis |
|-------------|------------|------|
| 400 | `RR201` | Amount change date conflicts with existing change |
| 400 | `RR202` | Cannot add change to paused/deleted rule |
| 400 | `RR203` | Effective date is before rule start date |
| 400 | `RR204` | Effective date is after rule end date |
| 400 | `RR205` | Currency mismatch with rule amount |

---

### 3.8 Delete Amount Change

#### Request: `DELETE /api/v1/recurring-rules/{ruleId}/amount-changes/{changeId}`

#### Response: `200 OK`
```json
{
  "changeId": "AC10000003",
  "deletedAt": "2026-02-25T15:00:00Z",
  "affectedOccurrences": 9,
  "message": "Amount change deleted. 9 future occurrences will revert to previous amount."
}
```

---

### 3.9 Pause Rule

#### Request: `POST /api/v1/recurring-rules/{ruleId}/pause`

```java
public record PauseRuleRequest(
    @Size(max = 200, message = "Reason cannot exceed 200 characters")
    String reason,

    LocalDate resumeDate  // opcjonalna data automatycznego wznowienia
) {}
```

#### Response: `200 OK`
```json
{
  "ruleId": "RR10000001",
  "status": "PAUSED",
  "pausedAt": "2026-02-25T16:00:00Z",
  "pauseReason": "Urlop bezpłatny",
  "scheduledResumeDate": "2026-04-01",
  "skippedOccurrences": 1,
  "message": "Rule paused. 1 occurrence (2026-03-10) will be skipped. Scheduled to resume on 2026-04-01."
}
```

---

### 3.10 Resume Rule

#### Request: `POST /api/v1/recurring-rules/{ruleId}/resume`

```java
public record ResumeRuleRequest(
    boolean generateSkipped  // czy wygenerować pominięte podczas pauzy
) {}
```

#### Response: `200 OK`
```json
{
  "ruleId": "RR10000001",
  "status": "ACTIVE",
  "resumedAt": "2026-02-25T17:00:00Z",
  "skippedOccurrencesDuringPause": 1,
  "regeneratedOccurrences": 0,
  "nextOccurrence": "2026-04-10",
  "message": "Rule resumed. Next occurrence: 2026-04-10."
}
```

---

## 4. Walidacje - podsumowanie

### 4.1 Bean Validation Annotations

| Annotation | Pola | Opis |
|------------|------|------|
| `@NotBlank` | name, cashFlowId, categoryName | Wymagane, nie puste |
| `@NotNull` | amount, type, recurrencePattern, startDate | Wymagane |
| `@Size` | name (1-100), description (0-500) | Długość tekstu |
| `@Pattern` | cashFlowId (`CF\d+`), currency (`[A-Z]{3}`) | Format |
| `@DecimalMin/Max` | amount.amount | Zakres kwoty |
| `@Digits` | amount.amount | Precyzja |
| `@Min/Max` | dayOfMonth (1-31), month (1-12) | Zakresy |
| `@FutureOrPresent` | startDate, effectiveDate | Nie w przeszłości |
| `@Future` | endDate | W przyszłości |

### 4.2 Custom Validations (w CommandHandler)

| Walidacja | Opis | Error Code |
|-----------|------|------------|
| Category exists | Sprawdzenie czy kategoria istnieje w CashFlow | RR004 |
| Category not archived | Sprawdzenie czy kategoria nie jest zarchiwizowana | RR005 |
| Category type match | Typ kategorii zgodny z typem reguły | RR006 |
| CashFlow exists | Sprawdzenie czy CashFlow istnieje | RR003 |
| CashFlow status | CashFlow musi być w statusie OPEN | RR007 |
| Amount currency | Waluta zgodna z walutą CashFlow | RR008 |
| Date consistency | endDate > startDate | RR009 |
| Pattern consistency | dayOfMonth ≤ 28 lub adjustForMonthEnd=true | RR010 |

---

## 5. HTTP Headers

### 5.1 Request Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Authorization` | Yes | `Bearer {jwt_token}` |
| `Content-Type` | Yes (POST/PUT) | `application/json` |
| `Accept` | No | `application/json` |
| `X-Request-Id` | No | UUID dla traceability |
| `X-Idempotency-Key` | No (POST) | UUID dla idempotentnych requestów |

### 5.2 Response Headers

| Header | Description |
|--------|-------------|
| `X-Request-Id` | Echo request ID lub wygenerowany UUID |
| `X-Correlation-Id` | ID do śledzenia w logach |
| `Retry-After` | Sekundy do ponowienia (przy 503) |
| `X-RateLimit-Remaining` | Pozostałe requesty w oknie |

---

## 6. Rate Limiting

| Endpoint | Limit | Window |
|----------|-------|--------|
| POST /recurring-rules | 10 | 1 minute |
| PUT /recurring-rules/* | 20 | 1 minute |
| DELETE /recurring-rules/* | 10 | 1 minute |
| GET /recurring-rules | 100 | 1 minute |
| GET /recurring-rules/* | 100 | 1 minute |

---

## 7. Controller Implementation Skeleton

```java
@RestController
@RequestMapping("/api/v1/recurring-rules")
@RequiredArgsConstructor
@Validated
@Tag(name = "Recurring Rules", description = "Manage recurring transaction rules")
public class RecurringRulesController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new recurring rule")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Rule created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "CashFlow not found"),
        @ApiResponse(responseCode = "503", description = "Service unavailable")
    })
    public RecurringRuleResponse createRule(
            @Valid @RequestBody CreateRecurringRuleRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserDetails userDetails) {

        var command = new CreateRecurringRuleCommand(
            request.cashFlowId(),
            request.name(),
            request.description(),
            request.amount().toDomain(),
            request.type(),
            request.categoryName(),
            request.recurrencePattern().toDomain(),
            request.startDate(),
            request.endDate(),
            userDetails.getUsername(),
            idempotencyKey
        );

        return commandGateway.send(command);
    }

    @GetMapping
    @Operation(summary = "List recurring rules for a CashFlow")
    public Page<RecurringRuleSummaryResponse> listRules(
            @RequestParam @Pattern(regexp = "CF\\d+") String cashFlowId,
            @RequestParam(required = false) RuleStatus status,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) String categoryName,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserDetails userDetails) {

        var query = new ListRecurringRulesQuery(
            cashFlowId, status, type, categoryName, pageable, userDetails.getUsername()
        );

        return queryGateway.send(query);
    }

    @GetMapping("/{ruleId}")
    @Operation(summary = "Get recurring rule details")
    public RecurringRuleResponse getRule(
            @PathVariable @Pattern(regexp = "RR\\d+") String ruleId,
            @AuthenticationPrincipal UserDetails userDetails) {

        var query = new GetRecurringRuleQuery(ruleId, userDetails.getUsername());
        return queryGateway.send(query);
    }

    @PutMapping("/{ruleId}")
    @Operation(summary = "Update a recurring rule")
    public UpdateRuleResponse updateRule(
            @PathVariable @Pattern(regexp = "RR\\d+") String ruleId,
            @Valid @RequestBody UpdateRecurringRuleRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        var command = new UpdateRecurringRuleCommand(
            ruleId,
            request.name(),
            request.description(),
            request.amount() != null ? request.amount().toDomain() : null,
            request.categoryName(),
            request.recurrencePattern() != null ? request.recurrencePattern().toDomain() : null,
            request.endDate(),
            request.applyToFutureOnly(),
            userDetails.getUsername()
        );

        return commandGateway.send(command);
    }

    @DeleteMapping("/{ruleId}")
    @Operation(summary = "Delete a recurring rule")
    public DeleteRuleResponse deleteRule(
            @PathVariable @Pattern(regexp = "RR\\d+") String ruleId,
            @RequestParam(defaultValue = "false") boolean deleteGeneratedTransactions,
            @AuthenticationPrincipal UserDetails userDetails) {

        var command = new DeleteRecurringRuleCommand(
            ruleId, deleteGeneratedTransactions, userDetails.getUsername()
        );

        return commandGateway.send(command);
    }

    @GetMapping("/{ruleId}/impact-preview")
    @Operation(summary = "Preview the impact of deleting a rule")
    public DeleteImpactPreviewResponse previewDeleteImpact(
            @PathVariable @Pattern(regexp = "RR\\d+") String ruleId,
            @AuthenticationPrincipal UserDetails userDetails) {

        var query = new PreviewDeleteImpactQuery(ruleId, userDetails.getUsername());
        return queryGateway.send(query);
    }

    @PostMapping("/{ruleId}/amount-changes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an amount change to a rule")
    public AddAmountChangeResponse addAmountChange(
            @PathVariable @Pattern(regexp = "RR\\d+") String ruleId,
            @Valid @RequestBody AddAmountChangeRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        var command = new AddAmountChangeCommand(
            ruleId,
            request.effectiveDate(),
            request.type(),
            request.newAmount().toDomain(),
            request.reason(),
            userDetails.getUsername()
        );

        return commandGateway.send(command);
    }

    @DeleteMapping("/{ruleId}/amount-changes/{changeId}")
    @Operation(summary = "Delete an amount change")
    public DeleteAmountChangeResponse deleteAmountChange(
            @PathVariable @Pattern(regexp = "RR\\d+") String ruleId,
            @PathVariable @Pattern(regexp = "AC\\d+") String changeId,
            @AuthenticationPrincipal UserDetails userDetails) {

        var command = new DeleteAmountChangeCommand(ruleId, changeId, userDetails.getUsername());
        return commandGateway.send(command);
    }

    @PostMapping("/{ruleId}/pause")
    @Operation(summary = "Pause a recurring rule")
    public PauseRuleResponse pauseRule(
            @PathVariable @Pattern(regexp = "RR\\d+") String ruleId,
            @Valid @RequestBody(required = false) PauseRuleRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        var command = new PauseRuleCommand(
            ruleId,
            request != null ? request.reason() : null,
            request != null ? request.resumeDate() : null,
            userDetails.getUsername()
        );

        return commandGateway.send(command);
    }

    @PostMapping("/{ruleId}/resume")
    @Operation(summary = "Resume a paused rule")
    public ResumeRuleResponse resumeRule(
            @PathVariable @Pattern(regexp = "RR\\d+") String ruleId,
            @Valid @RequestBody(required = false) ResumeRuleRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        var command = new ResumeRuleCommand(
            ruleId,
            request != null && request.generateSkipped(),
            userDetails.getUsername()
        );

        return commandGateway.send(command);
    }
}
```

---

## Następny dokument

Przejdź do [02-domain-model.md](./02-domain-model.md) aby zobaczyć model domenowy i eventy.
