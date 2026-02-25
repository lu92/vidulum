# User Journeys - Recurring Rules

**Powiązane:** [02-domain-model.md](./02-domain-model.md) | [Następny: 04-mongodb-schema.md](./04-mongodb-schema.md)

---

## 1. Journey: Tworzenie reguły cyklicznej

### 1.1 Scenariusz: Użytkownik tworzy regułę dla miesięcznego wynagrodzenia

**Aktor:** Użytkownik z istniejącym CashFlow
**Cel:** Utworzenie reguły generującej przychód 8500 PLN 10. dnia każdego miesiąca

```
┌─────────┐          ┌─────────────────┐          ┌──────────────┐          ┌─────────┐
│   UI    │          │ RecurringRules  │          │   CashFlow   │          │  Kafka  │
│         │          │   Controller    │          │   Service    │          │         │
└────┬────┘          └────────┬────────┘          └──────┬───────┘          └────┬────┘
     │                        │                          │                       │
     │ POST /recurring-rules  │                          │                       │
     │───────────────────────▶│                          │                       │
     │                        │                          │                       │
     │                        │ GET /cash-flow/{id}/categories                   │
     │                        │─────────────────────────▶│                       │
     │                        │                          │                       │
     │                        │ {categories: [...]}      │                       │
     │                        │◀─────────────────────────│                       │
     │                        │                          │                       │
     │                        │ Validate category exists │                       │
     │                        │ Validate category type   │                       │
     │                        │ Validate not archived    │                       │
     │                        │                          │                       │
     │                        │ Save RecurringRule       │                       │
     │                        │ (MongoDB)                │                       │
     │                        │                          │                       │
     │                        │ Publish RuleCreatedEvent │                       │
     │                        │─────────────────────────────────────────────────▶│
     │                        │                          │                       │
     │ 201 Created            │                          │                       │
     │ {ruleId: "RR10000001"} │                          │                       │
     │◀───────────────────────│                          │                       │
     │                        │                          │                       │
```

### 1.2 Request

```http
POST /api/v1/recurring-rules HTTP/1.1
Host: localhost:9090
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
Content-Type: application/json
X-Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000

{
  "cashFlowId": "CF10000001",
  "name": "Wynagrodzenie",
  "description": "Pensja miesięczna z głównego etatu",
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
  "startDate": "2026-03-10",
  "endDate": null
}
```

### 1.3 Przepływ w systemie

```
1. RecurringRulesController.createRule()
   │
   ├─► Walidacja Bean Validation (annotations)
   │   ├─ @NotBlank: cashFlowId, name, categoryName
   │   ├─ @NotNull: amount, type, recurrencePattern, startDate
   │   └─ @Valid: nested DTOs
   │
   ├─► CreateRecurringRuleCommandHandler.handle()
   │   │
   │   ├─► CategoryValidationService.validateCategory()
   │   │   │
   │   │   ├─► ResilientCashFlowHttpClient.getCategories()
   │   │   │   ├─ Retry: 3 attempts, exponential backoff
   │   │   │   ├─ Circuit Breaker: 50% failure rate threshold
   │   │   │   └─ Timeout: 5 seconds
   │   │   │
   │   │   ├─► Check category exists
   │   │   │   └─ If not: throw CategoryValidationException(RR004)
   │   │   │
   │   │   ├─► Check category not archived
   │   │   │   └─ If archived: throw CategoryValidationException(RR005)
   │   │   │
   │   │   └─► Check category type matches rule type
   │   │       └─ If mismatch: throw CategoryValidationException(RR006)
   │   │
   │   ├─► RecurringRuleIdGenerator.generate()
   │   │   └─ Returns: RecurringRuleId("RR10000001")
   │   │
   │   ├─► RecurringRule.create(...)
   │   │   ├─ Validates input parameters
   │   │   ├─ Creates RuleCreatedEvent
   │   │   └─ Applies event (sets initial state)
   │   │
   │   ├─► RecurringRuleRepository.save(rule)
   │   │   ├─ Converts to RecurringRuleDocument
   │   │   └─ MongoDB insert
   │   │
   │   └─► OutboxRepository.save(outboxEntry)
   │       └─ Stores RuleCreatedEvent for async publishing
   │
   └─► Return RecurringRuleResponse

2. OutboxProcessor (async, scheduled)
   │
   ├─► Fetch pending outbox entries
   │
   ├─► KafkaTemplate.send("recurring_rules", event)
   │
   └─► Mark outbox entry as SENT
```

### 1.4 Zmiany stanu

| Komponent | Przed | Po |
|-----------|-------|-----|
| `recurring_rules` collection | - | Nowy dokument z `status: ACTIVE` |
| `outbox` collection | - | Nowy wpis z `status: PENDING` |
| Kafka topic | - | `RuleCreatedEvent` (po przetworzeniu outbox) |

### 1.5 Response

```http
HTTP/1.1 201 Created
Content-Type: application/json
X-Request-Id: 550e8400-e29b-41d4-a716-446655440000

{
  "ruleId": "RR10000001",
  "cashFlowId": "CF10000001",
  "name": "Wynagrodzenie",
  "description": "Pensja miesięczna z głównego etatu",
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
  "startDate": "2026-03-10",
  "endDate": null,
  "status": "ACTIVE",
  "nextOccurrence": "2026-03-10",
  "amountChanges": [],
  "createdAt": "2026-02-25T10:30:00Z",
  "lastModifiedAt": "2026-02-25T10:30:00Z"
}
```

---

## 2. Journey: Automatyczne generowanie transakcji

### 2.1 Scenariusz: Scheduler generuje transakcję dla nadchodzącego wystąpienia

**Aktor:** System (RuleExecutionScheduler)
**Cel:** Wygenerowanie CashChange dla reguły na datę 2026-03-10

```
┌───────────────────┐     ┌─────────────────┐     ┌──────────────┐     ┌─────────┐
│ RuleExecution     │     │ RecurringRules  │     │   CashFlow   │     │  Kafka  │
│ Scheduler         │     │    Service      │     │   Service    │     │         │
└────────┬──────────┘     └────────┬────────┘     └──────┬───────┘     └────┬────┘
         │                         │                     │                  │
         │ @Scheduled (daily 6:00) │                     │                  │
         │─────────────────────────│                     │                  │
         │                         │                     │                  │
         │ Find rules with         │                     │                  │
         │ nextOccurrence = today  │                     │                  │
         │────────────────────────▶│                     │                  │
         │                         │                     │                  │
         │ [RR10000001]            │                     │                  │
         │◀────────────────────────│                     │                  │
         │                         │                     │                  │
         │ For each rule:          │                     │                  │
         │ generateTransaction()   │                     │                  │
         │────────────────────────▶│                     │                  │
         │                         │                     │                  │
         │                         │ POST /cash-flow/{id}/cash-changes     │
         │                         │ (with idempotency key)                │
         │                         │────────────────────▶│                  │
         │                         │                     │                  │
         │                         │ 201 Created         │                  │
         │                         │ {cashChangeId}      │                  │
         │                         │◀────────────────────│                  │
         │                         │                     │                  │
         │                         │ Record execution    │                  │
         │                         │ (SUCCESS)           │                  │
         │                         │                     │                  │
         │                         │ Publish ExecutionRecordedEvent        │
         │                         │─────────────────────────────────────▶│
         │                         │                     │                  │
         │ Done                    │                     │                  │
         │◀────────────────────────│                     │                  │
         │                         │                     │                  │
```

### 2.2 Przepływ w systemie

```
1. RuleExecutionScheduler.executeScheduledRules()
   │
   ├─► RecurringRuleRepository.findRulesForExecution(today)
   │   Query: { status: "ACTIVE", nextOccurrence: { $lte: today }}
   │
   └─► For each rule:
       │
       ├─► Check if already executed for date (idempotency)
       │   └─ If executed: skip
       │
       ├─► rule.getEffectiveAmount(today)
       │   └─ Applies amount changes
       │
       ├─► ResilientCashFlowHttpClient.createCashChange()
       │   │
       │   │  POST /cash-flow/{cashFlowId}/cash-changes
       │   │  X-Idempotency-Key: {ruleId}-{date}
       │   │  {
       │   │    "name": "Wynagrodzenie",
       │   │    "description": "Generated from recurring rule RR10000001",
       │   │    "money": { "amount": 8500.00, "currency": "PLN" },
       │   │    "type": "INFLOW",
       │   │    "categoryName": "Salary",
       │   │    "dueDate": "2026-03-10T00:00:00Z",
       │   │    "sourceRuleId": "RR10000001"
       │   │  }
       │   │
       │   ├─ Success: Returns CashChangeId
       │   │
       │   └─ Failure: Throws CashFlowServiceException
       │
       ├─► rule.recordExecution(date, SUCCESS, cashChangeId)
       │
       ├─► RecurringRuleRepository.save(rule)
       │
       └─► OutboxRepository.save(ExecutionRecordedEvent)

2. Error Handling:
   │
   ├─► If CashFlowServiceException:
   │   ├─ rule.recordExecution(date, FAILED, null, errorMessage)
   │   ├─ Log error with correlation ID
   │   └─ Schedule retry (FailedGenerationRecoveryService)
   │
   └─► If any other exception:
       ├─ Log error
       └─ Continue with next rule (don't fail entire batch)
```

### 2.3 Zmiany stanu

| Komponent | Przed | Po |
|-----------|-------|-----|
| RecurringRule.executions | {} | { "2026-03-10": { status: SUCCESS, cashChangeId: "CC10000100" }} |
| CashFlow.cashChanges | {...} | {..., "CC10000100": { ... }} |
| outbox | - | ExecutionRecordedEvent (PENDING) |

### 2.4 Wygenerowana transakcja w CashFlow

```json
{
  "cashChangeId": "CC10000100",
  "name": "Wynagrodzenie",
  "description": "Generated from recurring rule RR10000001",
  "money": {
    "amount": 8500.00,
    "currency": "PLN"
  },
  "type": "INFLOW",
  "categoryName": "Salary",
  "status": "PENDING",
  "dueDate": "2026-03-10T00:00:00Z",
  "paidDate": null,
  "sourceRuleId": "RR10000001",
  "created": "2026-03-10T06:00:00Z"
}
```

---

## 3. Journey: Edycja reguły z podglądem wpływu

### 3.1 Scenariusz: Użytkownik zmienia kwotę reguły i widzi wpływ na przyszłe transakcje

**Aktor:** Użytkownik
**Cel:** Zmiana kwoty z 8500 PLN na 9000 PLN dla przyszłych wystąpień

```
┌─────────┐          ┌─────────────────┐          ┌──────────────┐
│   UI    │          │ RecurringRules  │          │   MongoDB    │
│         │          │   Controller    │          │              │
└────┬────┘          └────────┬────────┘          └──────┬───────┘
     │                        │                          │
     │ GET /recurring-rules/{id}/impact-preview?amount=9000
     │───────────────────────▶│                          │
     │                        │                          │
     │                        │ Calculate affected       │
     │                        │ occurrences              │
     │                        │                          │
     │ {                      │                          │
     │   affectedOccurrences: 10,                        │
     │   totalDifference: 5000,                          │
     │   occurrences: [...]   │                          │
     │ }                      │                          │
     │◀───────────────────────│                          │
     │                        │                          │
     │ [User confirms]        │                          │
     │                        │                          │
     │ PUT /recurring-rules/{id}                         │
     │───────────────────────▶│                          │
     │                        │                          │
     │                        │ Validate category        │
     │                        │                          │
     │                        │ Update rule              │
     │                        │─────────────────────────▶│
     │                        │                          │
     │                        │ Publish RuleUpdatedEvent │
     │                        │                          │
     │ 200 OK                 │                          │
     │ { affectedOccurrences: 10 }                       │
     │◀───────────────────────│                          │
     │                        │                          │
```

### 3.2 Preview Request

```http
GET /api/v1/recurring-rules/RR10000001/update-preview?amount=9000 HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

### 3.3 Preview Response

```json
{
  "ruleId": "RR10000001",
  "currentAmount": {
    "amount": 8500.00,
    "currency": "PLN"
  },
  "newAmount": {
    "amount": 9000.00,
    "currency": "PLN"
  },
  "impact": {
    "affectedOccurrences": 10,
    "differencePerOccurrence": {
      "amount": 500.00,
      "currency": "PLN"
    },
    "totalDifference": {
      "amount": 5000.00,
      "currency": "PLN"
    },
    "affectedDates": [
      "2026-03-10",
      "2026-04-10",
      "2026-05-10",
      "2026-06-10",
      "2026-07-10",
      "2026-08-10",
      "2026-09-10",
      "2026-10-10",
      "2026-11-10",
      "2026-12-10"
    ]
  },
  "existingAmountChanges": []
}
```

### 3.4 Update Request

```http
PUT /api/v1/recurring-rules/RR10000001 HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
Content-Type: application/json

{
  "amount": {
    "amount": 9000.00,
    "currency": "PLN"
  },
  "applyToFutureOnly": true
}
```

### 3.5 Update Response

```json
{
  "ruleId": "RR10000001",
  "affectedOccurrences": 10,
  "message": "Rule updated successfully. 10 future occurrences will use the new amount of 9,000.00 PLN.",
  "rule": {
    "ruleId": "RR10000001",
    "name": "Wynagrodzenie",
    "amount": {
      "amount": 9000.00,
      "currency": "PLN"
    },
    "lastModifiedAt": "2026-02-25T14:30:00Z"
  }
}
```

---

## 4. Journey: Dodanie jednorazowej zmiany kwoty (premia)

### 4.1 Scenariusz: Użytkownik dodaje premię roczną w czerwcu

**Aktor:** Użytkownik
**Cel:** W czerwcu zamiast 8500 PLN ma być 18500 PLN (premia 10000 PLN)

```
┌─────────┐          ┌─────────────────┐          ┌──────────────┐
│   UI    │          │ RecurringRules  │          │   MongoDB    │
│         │          │   Controller    │          │              │
└────┬────┘          └────────┬────────┘          └──────┬───────┘
     │                        │                          │
     │ POST /recurring-rules/{id}/amount-changes         │
     │ {                      │                          │
     │   effectiveDate: "2026-06-10",                    │
     │   type: "ONE_TIME",    │                          │
     │   newAmount: { amount: 18500, currency: "PLN" },  │
     │   reason: "Premia roczna"                         │
     │ }                      │                          │
     │───────────────────────▶│                          │
     │                        │                          │
     │                        │ Validate date in range   │
     │                        │ Validate no conflict     │
     │                        │                          │
     │                        │ Add amount change        │
     │                        │─────────────────────────▶│
     │                        │                          │
     │ 201 Created            │                          │
     │ {                      │                          │
     │   changeId: "AC10000001",                         │
     │   message: "Amount change added. June 10, 2026 will use 18,500.00 PLN"
     │ }                      │                          │
     │◀───────────────────────│                          │
     │                        │                          │
```

### 4.2 Przepływ w systemie

```
1. AddAmountChangeCommandHandler.handle()
   │
   ├─► Validate effectiveDate >= startDate
   │   └─ If not: throw InvalidAmountChangeDateException
   │
   ├─► Validate effectiveDate <= endDate (if exists)
   │   └─ If not: throw InvalidAmountChangeDateException
   │
   ├─► Validate no conflicting ONE_TIME change on same date
   │   └─ If conflict: throw AmountChangeConflictException
   │
   ├─► rule.addAmountChange(...)
   │   ├─ Creates AmountChangeAddedEvent
   │   └─ Updates amountChanges map
   │
   ├─► RecurringRuleRepository.save(rule)
   │
   └─► OutboxRepository.save(event)
```

### 4.3 Stan reguły po dodaniu zmiany

```json
{
  "ruleId": "RR10000001",
  "baseAmount": {
    "amount": 8500.00,
    "currency": "PLN"
  },
  "amountChanges": [
    {
      "changeId": "AC10000001",
      "effectiveDate": "2026-06-10",
      "type": "ONE_TIME",
      "newAmount": {
        "amount": 18500.00,
        "currency": "PLN"
      },
      "reason": "Premia roczna",
      "createdAt": "2026-02-25T15:00:00Z"
    }
  ]
}
```

### 4.4 Efektywne kwoty po zmianach

| Data | Efektywna kwota | Źródło |
|------|-----------------|--------|
| 2026-03-10 | 8500 PLN | baseAmount |
| 2026-04-10 | 8500 PLN | baseAmount |
| 2026-05-10 | 8500 PLN | baseAmount |
| 2026-06-10 | **18500 PLN** | ONE_TIME AC10000001 |
| 2026-07-10 | 8500 PLN | baseAmount (wraca do bazowej) |

---

## 5. Journey: Dodanie stałej zmiany kwoty (podwyżka)

### 5.1 Scenariusz: Użytkownik dodaje stałą podwyżkę od lipca

```http
POST /api/v1/recurring-rules/RR10000001/amount-changes HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
Content-Type: application/json

{
  "effectiveDate": "2026-07-10",
  "type": "PERMANENT",
  "newAmount": {
    "amount": 9500.00,
    "currency": "PLN"
  },
  "reason": "Podwyżka roczna"
}
```

### 5.2 Efektywne kwoty po obu zmianach

| Data | Efektywna kwota | Źródło |
|------|-----------------|--------|
| 2026-03-10 | 8500 PLN | baseAmount |
| 2026-04-10 | 8500 PLN | baseAmount |
| 2026-05-10 | 8500 PLN | baseAmount |
| 2026-06-10 | **18500 PLN** | ONE_TIME (premia) |
| 2026-07-10 | **9500 PLN** | PERMANENT (podwyżka) |
| 2026-08-10 | 9500 PLN | PERMANENT (kontynuacja) |
| ... | 9500 PLN | PERMANENT (kontynuacja) |

### 5.3 Algorytm getEffectiveAmount(date)

```java
public Money getEffectiveAmount(LocalDate date) {
    Money effectiveAmount = baseAmount;

    // Sortuj zmiany chronologicznie
    List<AmountChange> sortedChanges = amountChanges.values().stream()
            .sorted(Comparator.comparing(AmountChange::effectiveDate))
            .toList();

    for (AmountChange change : sortedChanges) {
        // Zmiana musi być <= od sprawdzanej daty
        if (!change.effectiveDate().isAfter(date)) {
            if (change.type() == AmountChangeType.PERMANENT) {
                // PERMANENT: nadpisuje od tej daty na zawsze
                effectiveAmount = change.newAmount();
            } else if (change.type() == AmountChangeType.ONE_TIME
                       && change.effectiveDate().equals(date)) {
                // ONE_TIME: tylko dla dokładnie tej daty
                effectiveAmount = change.newAmount();
            }
        }
    }

    return effectiveAmount;
}
```

---

## 6. Journey: Usunięcie reguły z podglądem wpływu

### 6.1 Scenariusz: Użytkownik chce usunąć regułę i widzi ostrzeżenia

```
┌─────────┐          ┌─────────────────┐          ┌──────────────┐
│   UI    │          │ RecurringRules  │          │   MongoDB    │
│         │          │   Controller    │          │              │
└────┬────┘          └────────┬────────┘          └──────┬───────┘
     │                        │                          │
     │ GET /recurring-rules/{id}/impact-preview          │
     │───────────────────────▶│                          │
     │                        │                          │
     │                        │ Calculate:               │
     │                        │ - Future occurrences     │
     │                        │ - Generated transactions │
     │                        │ - Forecast impact        │
     │                        │                          │
     │ {                      │                          │
     │   futureOccurrences: { count: 10, total: 95000 }, │
     │   generatedTransactions: { total: 2, deletable: 0 },
     │   warnings: [...]      │                          │
     │ }                      │                          │
     │◀───────────────────────│                          │
     │                        │                          │
     │ [User sees warning modal]                         │
     │ [User confirms deletion]                          │
     │                        │                          │
     │ DELETE /recurring-rules/{id}                      │
     │───────────────────────▶│                          │
     │                        │                          │
     │                        │ Soft delete rule         │
     │                        │ (status = DELETED)       │
     │                        │─────────────────────────▶│
     │                        │                          │
     │                        │ Publish RuleDeletedEvent │
     │                        │                          │
     │ 200 OK                 │                          │
     │ { deletedAt: "...", preservedTransactions: 2 }    │
     │◀───────────────────────│                          │
     │                        │                          │
```

### 6.2 Preview Response

```json
{
  "ruleId": "RR10000001",
  "ruleName": "Wynagrodzenie",
  "impact": {
    "futureOccurrences": {
      "count": 10,
      "totalAmount": {
        "amount": 95000.00,
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
      "affectedMonths": [
        "2026-03", "2026-04", "2026-05", "2026-06", "2026-07",
        "2026-08", "2026-09", "2026-10", "2026-11", "2026-12"
      ],
      "projectedBalanceReduction": {
        "amount": 95000.00,
        "currency": "PLN"
      }
    }
  },
  "warnings": [
    {
      "type": "CONFIRMED_TRANSACTIONS",
      "message": "This rule has 2 confirmed transactions that cannot be deleted",
      "severity": "INFO"
    },
    {
      "type": "FORECAST_IMPACT",
      "message": "Deleting this rule will remove 95,000.00 PLN from your 10-month forecast",
      "severity": "WARNING"
    }
  ],
  "recommendations": [
    "Consider setting an end date instead of deleting to stop future occurrences",
    "Consider pausing the rule if you may need it again later"
  ]
}
```

### 6.3 Delete Response

```json
{
  "ruleId": "RR10000001",
  "status": "DELETED",
  "deletedAt": "2026-02-25T16:00:00Z",
  "affectedTransactions": {
    "total": 2,
    "deleted": 0,
    "preserved": 2,
    "preservedReason": "All transactions were already confirmed"
  },
  "message": "Rule 'Wynagrodzenie' has been deleted. 2 confirmed transactions were preserved. Future occurrences will not be generated."
}
```

---

## 7. Journey: Obsługa zarchiwizowanej kategorii

### 7.1 Scenariusz: Kategoria używana przez regułę zostaje zarchiwizowana

```
┌─────────┐     ┌──────────────┐     ┌─────────┐     ┌─────────────────┐
│CashFlow │     │    Kafka     │     │Recurring│     │  RecurringRule  │
│ Service │     │              │     │ Rules   │     │                 │
└────┬────┘     └──────┬───────┘     │ Listener│     └────────┬────────┘
     │                 │             └────┬────┘              │
     │ CategoryArchivedEvent              │                   │
     │────────────────▶│                  │                   │
     │                 │                  │                   │
     │                 │ Consume event    │                   │
     │                 │─────────────────▶│                   │
     │                 │                  │                   │
     │                 │                  │ Find rules using  │
     │                 │                  │ this category     │
     │                 │                  │──────────────────▶│
     │                 │                  │                   │
     │                 │                  │ [RR10000001,      │
     │                 │                  │  RR10000005]      │
     │                 │                  │◀──────────────────│
     │                 │                  │                   │
     │                 │                  │ For each rule:    │
     │                 │                  │ rule.handleCategoryArchived()
     │                 │                  │──────────────────▶│
     │                 │                  │                   │
     │                 │                  │ Status: ACTIVE → PAUSED
     │                 │                  │ Reason: "Category archived"
     │                 │                  │◀──────────────────│
     │                 │                  │                   │
     │                 │                  │ Save rules        │
     │                 │                  │                   │
     │                 │                  │ Notify user       │
     │                 │                  │ (email/in-app)    │
     │                 │                  │                   │
```

### 7.2 Kafka Event

```json
{
  "eventType": "CategoryArchivedEvent",
  "cashFlowId": "CF10000001",
  "categoryName": "Salary",
  "categoryType": "INFLOW",
  "archivedAt": "2026-02-25T17:00:00Z"
}
```

### 7.3 Stan reguły po obsłużeniu eventu

```json
{
  "ruleId": "RR10000001",
  "status": "PAUSED",
  "pauseInfo": {
    "reason": "Category 'Salary' was archived in CashFlow",
    "pausedAt": "2026-02-25T17:00:05Z",
    "scheduledResumeDate": null
  }
}
```

### 7.4 Notyfikacja użytkownika

```json
{
  "type": "RULE_AUTO_PAUSED",
  "userId": "U10000001",
  "title": "Recurring rule paused",
  "message": "Your recurring rule 'Wynagrodzenie' has been automatically paused because the category 'Salary' was archived. You can resume the rule after selecting a new category.",
  "actions": [
    {
      "label": "Edit Rule",
      "url": "/recurring-rules/RR10000001/edit"
    },
    {
      "label": "View Archived Categories",
      "url": "/cash-flow/CF10000001/categories?status=archived"
    }
  ]
}
```

---

## 8. Journey: Wznowienie reguły z wygenerowaniem pominiętych

### 8.1 Scenariusz: Użytkownik wznawia regułę po pauzie i chce wygenerować pominięte transakcje

```http
POST /api/v1/recurring-rules/RR10000001/resume HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
Content-Type: application/json

{
  "generateSkipped": true
}
```

### 8.2 Przepływ

```
1. ResumeRuleCommandHandler.handle()
   │
   ├─► Validate rule is PAUSED
   │
   ├─► Calculate skipped occurrences during pause
   │   └─ pauseInfo.pausedAt to now
   │
   ├─► If generateSkipped = true:
   │   │
   │   └─► For each skipped date:
   │       ├─ ResilientCashFlowHttpClient.createCashChange()
   │       └─ rule.recordExecution(date, SUCCESS, cashChangeId)
   │
   ├─► rule.resume(generateSkipped)
   │   ├─ status = ACTIVE
   │   └─ pauseInfo = null
   │
   └─► RecurringRuleRepository.save(rule)
```

### 8.3 Response

```json
{
  "ruleId": "RR10000001",
  "status": "ACTIVE",
  "resumedAt": "2026-04-01T10:00:00Z",
  "skippedOccurrencesDuringPause": 1,
  "regeneratedOccurrences": 1,
  "regeneratedTransactions": [
    {
      "scheduledDate": "2026-03-10",
      "cashChangeId": "CC10000150",
      "amount": {
        "amount": 8500.00,
        "currency": "PLN"
      }
    }
  ],
  "nextOccurrence": "2026-04-10",
  "message": "Rule resumed. 1 skipped occurrence was regenerated. Next occurrence: 2026-04-10."
}
```

---

## 9. Journey: Obsługa błędu generowania (retry)

### 9.1 Scenariusz: CashFlow service jest niedostępny podczas generowania

```
┌───────────────────┐     ┌─────────────────┐     ┌──────────────┐
│ RuleExecution     │     │ RecurringRules  │     │   CashFlow   │
│ Scheduler         │     │    Service      │     │   Service    │
└────────┬──────────┘     └────────┬────────┘     └──────┬───────┘
         │                         │                     │
         │ generateTransaction()   │                     │
         │────────────────────────▶│                     │
         │                         │                     │
         │                         │ POST /cash-flow/.../cash-changes
         │                         │────────────────────▶│
         │                         │                     │
         │                         │ 503 Service Unavailable
         │                         │◀────────────────────│
         │                         │                     │
         │                         │ Retry 1/3...        │
         │                         │────────────────────▶│
         │                         │                     │
         │                         │ 503 Service Unavailable
         │                         │◀────────────────────│
         │                         │                     │
         │                         │ Retry 2/3...        │
         │                         │────────────────────▶│
         │                         │                     │
         │                         │ 503 Service Unavailable
         │                         │◀────────────────────│
         │                         │                     │
         │                         │ Circuit breaker OPEN│
         │                         │                     │
         │                         │ Record execution    │
         │                         │ (FAILED)            │
         │                         │                     │
         │ Failed                  │                     │
         │◀────────────────────────│                     │
         │                         │                     │
```

### 9.2 Stan po błędzie

```json
{
  "ruleId": "RR10000001",
  "executions": {
    "2026-03-10": {
      "scheduledDate": "2026-03-10",
      "status": "FAILED",
      "executedAt": "2026-03-10T06:00:30Z",
      "generatedCashChangeId": null,
      "errorMessage": "CashFlow service unavailable after 3 retries"
    }
  }
}
```

### 9.3 Recovery przez FailedGenerationRecoveryService

```
┌───────────────────────┐     ┌─────────────────┐     ┌──────────────┐
│ FailedGeneration      │     │ RecurringRules  │     │   CashFlow   │
│ RecoveryService       │     │    Service      │     │   Service    │
└────────┬──────────────┘     └────────┬────────┘     └──────┬───────┘
         │                             │                     │
         │ @Scheduled (every 15 min)   │                     │
         │─────────────────────────────│                     │
         │                             │                     │
         │ Find failed executions      │                     │
         │ from last 24h               │                     │
         │────────────────────────────▶│                     │
         │                             │                     │
         │ [{ ruleId, date, attempt }] │                     │
         │◀────────────────────────────│                     │
         │                             │                     │
         │ For each:                   │                     │
         │ retryGeneration()           │                     │
         │────────────────────────────▶│                     │
         │                             │                     │
         │                             │ POST /cash-flow/.../cash-changes
         │                             │────────────────────▶│
         │                             │                     │
         │                             │ 201 Created         │
         │                             │◀────────────────────│
         │                             │                     │
         │                             │ Update execution:   │
         │                             │ FAILED → SUCCESS    │
         │                             │                     │
         │ Recovered                   │                     │
         │◀────────────────────────────│                     │
         │                             │                     │
```

---

## Następny dokument

Przejdź do [04-mongodb-schema.md](./04-mongodb-schema.md) aby zobaczyć schemat MongoDB.
