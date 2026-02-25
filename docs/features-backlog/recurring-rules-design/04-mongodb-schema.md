# MongoDB Schema - Recurring Rules

**Powiązane:** [03-user-journeys.md](./03-user-journeys.md) | [Następny: 05-bounded-context-integration.md](./05-bounded-context-integration.md)

---

## 1. Kolekcje

### 1.1 Diagram kolekcji

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          MongoDB - vidulum DB                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────┐    ┌─────────────────────────┐            │
│  │    recurring_rules      │    │         outbox          │            │
│  │    (aggregate)          │    │    (transactional)      │            │
│  ├─────────────────────────┤    ├─────────────────────────┤            │
│  │ _id: ruleId             │    │ _id: UUID               │            │
│  │ cashFlowId              │    │ aggregateType           │            │
│  │ userId                  │    │ aggregateId             │            │
│  │ name                    │    │ eventType               │            │
│  │ description             │    │ payload                 │            │
│  │ baseAmount              │    │ status                  │            │
│  │ type                    │    │ createdAt               │            │
│  │ categoryName            │    │ processedAt             │            │
│  │ recurrencePattern       │    │ retryCount              │            │
│  │ startDate               │    └─────────────────────────┘            │
│  │ endDate                 │                                           │
│  │ status                  │    ┌─────────────────────────┐            │
│  │ amountChanges[]         │    │   recurring_rule_ids    │            │
│  │ executions{}            │    │    (sequence)           │            │
│  │ pauseInfo               │    ├─────────────────────────┤            │
│  │ version                 │    │ _id: "recurring_rule"   │            │
│  │ createdAt               │    │ sequence: Long          │            │
│  │ lastModifiedAt          │    └─────────────────────────┘            │
│  └─────────────────────────┘                                           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Kolekcja: recurring_rules

### 2.1 Schema

```javascript
{
  // Primary key - prefixed ID
  _id: "RR10000001",

  // Foreign keys
  cashFlowId: "CF10000001",
  userId: "U10000001",

  // Basic info
  name: "Wynagrodzenie",
  description: "Pensja miesięczna z głównego etatu",

  // Amount
  baseAmount: {
    amount: NumberDecimal("8500.00"),
    currency: "PLN"
  },

  // Type
  type: "INFLOW",  // INFLOW | OUTFLOW

  // Category
  categoryName: "Salary",

  // Recurrence pattern (polymorphic embedded document)
  recurrencePattern: {
    type: "MONTHLY",  // DAILY | WEEKLY | MONTHLY | YEARLY
    // Fields depend on type:
    // DAILY: { intervalDays: 1 }
    // WEEKLY: { dayOfWeek: "MONDAY", intervalWeeks: 1 }
    // MONTHLY: { dayOfMonth: 10, intervalMonths: 1, adjustForMonthEnd: false }
    // YEARLY: { month: 6, dayOfMonth: 15 }
    dayOfMonth: 10,
    intervalMonths: 1,
    adjustForMonthEnd: false
  },

  // Date range
  startDate: ISODate("2026-01-10"),
  endDate: null,  // null = indefinite

  // Status
  status: "ACTIVE",  // ACTIVE | PAUSED | COMPLETED | DELETED

  // Amount changes (embedded array)
  amountChanges: [
    {
      changeId: "AC10000001",
      effectiveDate: ISODate("2026-06-10"),
      type: "ONE_TIME",  // ONE_TIME | PERMANENT
      newAmount: {
        amount: NumberDecimal("18500.00"),
        currency: "PLN"
      },
      reason: "Premia roczna",
      createdAt: ISODate("2026-02-25T15:00:00Z")
    },
    {
      changeId: "AC10000002",
      effectiveDate: ISODate("2026-07-10"),
      type: "PERMANENT",
      newAmount: {
        amount: NumberDecimal("9500.00"),
        currency: "PLN"
      },
      reason: "Podwyżka",
      createdAt: ISODate("2026-02-25T15:30:00Z")
    }
  ],

  // Executions (embedded map - date as key)
  executions: {
    "2026-01-10": {
      status: "SUCCESS",
      executedAt: ISODate("2026-01-10T06:00:00Z"),
      generatedCashChangeId: "CC10000020",
      errorMessage: null
    },
    "2026-02-10": {
      status: "SUCCESS",
      executedAt: ISODate("2026-02-10T06:00:05Z"),
      generatedCashChangeId: "CC10000050",
      errorMessage: null
    },
    "2026-03-10": {
      status: "FAILED",
      executedAt: ISODate("2026-03-10T06:00:30Z"),
      generatedCashChangeId: null,
      errorMessage: "CashFlow service unavailable"
    }
  },

  // Pause info (null if not paused)
  pauseInfo: null,
  // When paused:
  // pauseInfo: {
  //   reason: "Urlop bezpłatny",
  //   pausedAt: ISODate("2026-02-25T16:00:00Z"),
  //   scheduledResumeDate: ISODate("2026-04-01")
  // },

  // Optimistic locking
  version: 5,

  // Audit
  createdAt: ISODate("2026-01-05T10:30:00Z"),
  lastModifiedAt: ISODate("2026-02-25T15:30:00Z"),

  // Idempotency (optional, for create operations)
  idempotencyKey: "550e8400-e29b-41d4-a716-446655440000"
}
```

### 2.2 Indeksy

```javascript
// Primary index (automatic on _id)
// db.recurring_rules.createIndex({ _id: 1 })

// Composite index for listing rules by CashFlow
db.recurring_rules.createIndex(
  { cashFlowId: 1, status: 1, createdAt: -1 },
  { name: "idx_recurring_rules_cashflow_status_created" }
)

// Index for finding rules to execute
db.recurring_rules.createIndex(
  { status: 1, "recurrencePattern.type": 1 },
  {
    name: "idx_recurring_rules_active_pattern",
    partialFilterExpression: { status: "ACTIVE" }
  }
)

// Index for category sync (finding rules using specific category)
db.recurring_rules.createIndex(
  { cashFlowId: 1, categoryName: 1, status: 1 },
  { name: "idx_recurring_rules_category" }
)

// Index for user queries
db.recurring_rules.createIndex(
  { userId: 1, status: 1 },
  { name: "idx_recurring_rules_user_status" }
)

// Unique index for idempotency
db.recurring_rules.createIndex(
  { idempotencyKey: 1 },
  {
    name: "idx_recurring_rules_idempotency",
    unique: true,
    sparse: true  // Only index documents with idempotencyKey
  }
)

// Index for finding rules with failed executions (recovery)
db.recurring_rules.createIndex(
  { "executions.status": 1, lastModifiedAt: -1 },
  {
    name: "idx_recurring_rules_failed_executions",
    partialFilterExpression: { status: "ACTIVE" }
  }
)

// TTL index for soft-deleted rules (optional cleanup after 90 days)
db.recurring_rules.createIndex(
  { lastModifiedAt: 1 },
  {
    name: "idx_recurring_rules_deleted_ttl",
    expireAfterSeconds: 7776000,  // 90 days
    partialFilterExpression: { status: "DELETED" }
  }
)
```

### 2.3 Validation Schema

```javascript
db.createCollection("recurring_rules", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["_id", "cashFlowId", "userId", "name", "baseAmount", "type",
                 "categoryName", "recurrencePattern", "startDate", "status",
                 "version", "createdAt", "lastModifiedAt"],
      properties: {
        _id: {
          bsonType: "string",
          pattern: "^RR\\d{8}$",
          description: "Rule ID must match pattern RR followed by 8 digits"
        },
        cashFlowId: {
          bsonType: "string",
          pattern: "^CF\\d+$"
        },
        userId: {
          bsonType: "string",
          pattern: "^U\\d+$"
        },
        name: {
          bsonType: "string",
          minLength: 1,
          maxLength: 100
        },
        description: {
          bsonType: ["string", "null"],
          maxLength: 500
        },
        baseAmount: {
          bsonType: "object",
          required: ["amount", "currency"],
          properties: {
            amount: { bsonType: "decimal" },
            currency: {
              bsonType: "string",
              pattern: "^[A-Z]{3}$"
            }
          }
        },
        type: {
          enum: ["INFLOW", "OUTFLOW"]
        },
        categoryName: {
          bsonType: "string",
          minLength: 1,
          maxLength: 100
        },
        recurrencePattern: {
          bsonType: "object",
          required: ["type"],
          properties: {
            type: { enum: ["DAILY", "WEEKLY", "MONTHLY", "YEARLY"] }
          }
        },
        startDate: { bsonType: "date" },
        endDate: { bsonType: ["date", "null"] },
        status: {
          enum: ["ACTIVE", "PAUSED", "COMPLETED", "DELETED"]
        },
        amountChanges: {
          bsonType: "array",
          items: {
            bsonType: "object",
            required: ["changeId", "effectiveDate", "type", "newAmount", "createdAt"],
            properties: {
              changeId: { bsonType: "string", pattern: "^AC\\d{8}$" },
              type: { enum: ["ONE_TIME", "PERMANENT"] }
            }
          }
        },
        executions: {
          bsonType: "object"
        },
        version: {
          bsonType: "long",
          minimum: 0
        }
      }
    }
  }
})
```

---

## 3. Kolekcja: outbox

### 3.1 Schema

```javascript
{
  _id: UUID("550e8400-e29b-41d4-a716-446655440001"),

  // Aggregate info
  aggregateType: "RecurringRule",
  aggregateId: "RR10000001",

  // Event info
  eventType: "RuleCreatedEvent",
  payload: {
    ruleId: "RR10000001",
    cashFlowId: "CF10000001",
    userId: "U10000001",
    name: "Wynagrodzenie",
    description: "Pensja miesięczna",
    amount: {
      amount: NumberDecimal("8500.00"),
      currency: "PLN"
    },
    type: "INFLOW",
    categoryName: "Salary",
    recurrencePattern: {
      type: "MONTHLY",
      dayOfMonth: 10,
      intervalMonths: 1,
      adjustForMonthEnd: false
    },
    startDate: "2026-01-10",
    endDate: null,
    createdAt: "2026-01-05T10:30:00Z"
  },

  // Processing status
  status: "PENDING",  // PENDING | PROCESSING | SENT | FAILED

  // Kafka topic
  targetTopic: "recurring_rules",

  // Timestamps
  createdAt: ISODate("2026-01-05T10:30:00Z"),
  processedAt: null,

  // Retry info
  retryCount: 0,
  maxRetries: 5,
  nextRetryAt: null,
  lastError: null
}
```

### 3.2 Indeksy

```javascript
// Index for fetching pending events
db.outbox.createIndex(
  { status: 1, createdAt: 1 },
  {
    name: "idx_outbox_pending",
    partialFilterExpression: { status: { $in: ["PENDING", "FAILED"] } }
  }
)

// Index for retry processing
db.outbox.createIndex(
  { status: 1, nextRetryAt: 1 },
  {
    name: "idx_outbox_retry",
    partialFilterExpression: { status: "FAILED" }
  }
)

// TTL index for cleanup (remove sent events after 7 days)
db.outbox.createIndex(
  { processedAt: 1 },
  {
    name: "idx_outbox_sent_ttl",
    expireAfterSeconds: 604800,  // 7 days
    partialFilterExpression: { status: "SENT" }
  }
)

// Unique constraint to prevent duplicate events
db.outbox.createIndex(
  { aggregateId: 1, eventType: 1, "payload.occurredAt": 1 },
  {
    name: "idx_outbox_unique_event",
    unique: true
  }
)
```

---

## 4. Kolekcja: recurring_rule_ids (sequence)

### 4.1 Schema

```javascript
{
  _id: "recurring_rule",
  sequence: NumberLong(10000001)
}
```

### 4.2 Atomic ID Generation

```javascript
// Generate next ID atomically
db.recurring_rule_ids.findOneAndUpdate(
  { _id: "recurring_rule" },
  { $inc: { sequence: 1 } },
  { returnDocument: "after", upsert: true }
)
// Returns: { _id: "recurring_rule", sequence: 10000002 }
// New rule ID: "RR10000002"
```

---

## 5. Document Mapping

### 5.1 RecurringRuleDocument (Java)

```java
@Document(collection = "recurring_rules")
@TypeAlias("RecurringRule")
public class RecurringRuleDocument {

    @Id
    private String id;  // RR10000001

    @Indexed
    private String cashFlowId;

    @Indexed
    private String userId;

    private String name;

    private String description;

    private MoneyDocument baseAmount;

    private String type;  // INFLOW, OUTFLOW

    @Indexed
    private String categoryName;

    private RecurrencePatternDocument recurrencePattern;

    private LocalDate startDate;

    private LocalDate endDate;

    @Indexed
    private String status;  // ACTIVE, PAUSED, COMPLETED, DELETED

    private List<AmountChangeDocument> amountChanges = new ArrayList<>();

    private Map<String, ExecutionDocument> executions = new HashMap<>();

    private PauseInfoDocument pauseInfo;

    @Version
    private Long version;

    private ZonedDateTime createdAt;

    private ZonedDateTime lastModifiedAt;

    private String idempotencyKey;

    // Mapping methods
    public static RecurringRuleDocument fromDomain(RecurringRuleSnapshot snapshot) {
        RecurringRuleDocument doc = new RecurringRuleDocument();
        doc.id = snapshot.ruleId().id();
        doc.cashFlowId = snapshot.cashFlowId().id();
        doc.userId = snapshot.userId().getId();
        doc.name = snapshot.name().value();
        doc.description = snapshot.description() != null ? snapshot.description().value() : null;
        doc.baseAmount = MoneyDocument.from(snapshot.baseAmount());
        doc.type = snapshot.type().name();
        doc.categoryName = snapshot.categoryName().name();
        doc.recurrencePattern = RecurrencePatternDocument.from(snapshot.recurrencePattern());
        doc.startDate = snapshot.startDate();
        doc.endDate = snapshot.endDate();
        doc.status = snapshot.status().name();
        doc.amountChanges = snapshot.amountChanges().values().stream()
                .map(AmountChangeDocument::from)
                .collect(Collectors.toList());
        doc.executions = snapshot.executions().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> ExecutionDocument.from(e.getValue())
                ));
        doc.pauseInfo = snapshot.pauseInfo() != null
                ? PauseInfoDocument.from(snapshot.pauseInfo()) : null;
        doc.version = snapshot.version();
        doc.createdAt = snapshot.createdAt();
        doc.lastModifiedAt = snapshot.lastModifiedAt();
        return doc;
    }

    public RecurringRuleSnapshot toDomain() {
        return new RecurringRuleSnapshot(
                new RecurringRuleId(id),
                new CashFlowId(cashFlowId),
                UserId.of(userId),
                new Name(name),
                description != null ? new Description(description) : null,
                baseAmount.toDomain(),
                Type.valueOf(type),
                new CategoryName(categoryName),
                recurrencePattern.toDomain(),
                startDate,
                endDate,
                RuleStatus.valueOf(status),
                amountChanges.stream()
                        .collect(Collectors.toMap(
                                ac -> new AmountChangeId(ac.getChangeId()),
                                AmountChangeDocument::toDomain
                        )),
                executions.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> LocalDate.parse(e.getKey()),
                                e -> e.getValue().toDomain()
                        )),
                pauseInfo != null ? pauseInfo.toDomain() : null,
                version,
                createdAt,
                lastModifiedAt
        );
    }
}
```

### 5.2 Embedded Documents

```java
// MoneyDocument
public class MoneyDocument {
    private BigDecimal amount;
    private String currency;

    public static MoneyDocument from(Money money) {
        MoneyDocument doc = new MoneyDocument();
        doc.amount = money.amount();
        doc.currency = money.currency();
        return doc;
    }

    public Money toDomain() {
        return Money.of(amount, currency);
    }
}

// RecurrencePatternDocument
public class RecurrencePatternDocument {
    private String type;

    // DAILY
    private Integer intervalDays;

    // WEEKLY
    private String dayOfWeek;
    private Integer intervalWeeks;

    // MONTHLY
    private Integer dayOfMonth;
    private Integer intervalMonths;
    private Boolean adjustForMonthEnd;

    // YEARLY
    private Integer month;
    // dayOfMonth reused from MONTHLY

    public static RecurrencePatternDocument from(RecurrencePattern pattern) {
        RecurrencePatternDocument doc = new RecurrencePatternDocument();
        doc.type = pattern.type().name();

        switch (pattern) {
            case DailyPattern daily -> {
                doc.intervalDays = daily.intervalDays();
            }
            case WeeklyPattern weekly -> {
                doc.dayOfWeek = weekly.dayOfWeek().name();
                doc.intervalWeeks = weekly.intervalWeeks();
            }
            case MonthlyPattern monthly -> {
                doc.dayOfMonth = monthly.dayOfMonth();
                doc.intervalMonths = monthly.intervalMonths();
                doc.adjustForMonthEnd = monthly.adjustForMonthEnd();
            }
            case YearlyPattern yearly -> {
                doc.month = yearly.month();
                doc.dayOfMonth = yearly.dayOfMonth();
            }
        }

        return doc;
    }

    public RecurrencePattern toDomain() {
        return switch (RecurrenceType.valueOf(type)) {
            case DAILY -> new DailyPattern(intervalDays);
            case WEEKLY -> new WeeklyPattern(DayOfWeek.valueOf(dayOfWeek), intervalWeeks);
            case MONTHLY -> new MonthlyPattern(dayOfMonth, intervalMonths, adjustForMonthEnd);
            case YEARLY -> new YearlyPattern(month, dayOfMonth);
        };
    }
}

// AmountChangeDocument
public class AmountChangeDocument {
    private String changeId;
    private LocalDate effectiveDate;
    private String type;
    private MoneyDocument newAmount;
    private String reason;
    private ZonedDateTime createdAt;

    // from() and toDomain() methods...
}

// ExecutionDocument
public class ExecutionDocument {
    private String status;
    private ZonedDateTime executedAt;
    private String generatedCashChangeId;
    private String errorMessage;

    // from() and toDomain() methods...
}

// PauseInfoDocument
public class PauseInfoDocument {
    private String reason;
    private ZonedDateTime pausedAt;
    private LocalDate scheduledResumeDate;

    // from() and toDomain() methods...
}
```

---

## 6. Repository Queries

### 6.1 Spring Data MongoDB Repository

```java
public interface RecurringRuleMongoRepository extends MongoRepository<RecurringRuleDocument, String> {

    // Find rules for a CashFlow
    Page<RecurringRuleDocument> findByCashFlowIdAndStatus(
            String cashFlowId,
            String status,
            Pageable pageable
    );

    // Find rules for a CashFlow with multiple statuses
    Page<RecurringRuleDocument> findByCashFlowIdAndStatusIn(
            String cashFlowId,
            List<String> statuses,
            Pageable pageable
    );

    // Find all active rules for a CashFlow
    List<RecurringRuleDocument> findByCashFlowIdAndStatus(String cashFlowId, String status);

    // Find rules using a specific category
    List<RecurringRuleDocument> findByCashFlowIdAndCategoryNameAndStatusNot(
            String cashFlowId,
            String categoryName,
            String excludeStatus
    );

    // Find by idempotency key
    Optional<RecurringRuleDocument> findByIdempotencyKey(String idempotencyKey);

    // Count active rules for user
    long countByUserIdAndStatus(String userId, String status);
}
```

### 6.2 Custom Repository Queries

```java
@Repository
@RequiredArgsConstructor
public class RecurringRuleCustomRepository {

    private final MongoTemplate mongoTemplate;

    /**
     * Find active rules that need execution for given date
     */
    public List<RecurringRuleDocument> findRulesForExecution(LocalDate date) {
        Query query = new Query();
        query.addCriteria(Criteria.where("status").is("ACTIVE"));
        query.addCriteria(Criteria.where("startDate").lte(date));
        query.addCriteria(new Criteria().orOperator(
                Criteria.where("endDate").isNull(),
                Criteria.where("endDate").gte(date)
        ));
        // Exclude already executed
        query.addCriteria(Criteria.where("executions." + date.toString()).exists(false));

        return mongoTemplate.find(query, RecurringRuleDocument.class);
    }

    /**
     * Find rules with failed executions in last N hours
     */
    public List<RecurringRuleDocument> findRulesWithFailedExecutions(int hoursBack) {
        Instant since = Instant.now().minus(Duration.ofHours(hoursBack));

        Query query = new Query();
        query.addCriteria(Criteria.where("status").is("ACTIVE"));

        // MongoDB aggregation for checking execution status
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("status").is("ACTIVE")),
            Aggregation.project()
                .and(ObjectOperators.ObjectToArray.valueOfToArray("executions"))
                .as("executionArray"),
            Aggregation.unwind("executionArray"),
            Aggregation.match(Criteria.where("executionArray.v.status").is("FAILED")
                .and("executionArray.v.executedAt").gte(Date.from(since))),
            Aggregation.group("_id")
        );

        return mongoTemplate.aggregate(aggregation, "recurring_rules", RecurringRuleDocument.class)
                .getMappedResults();
    }

    /**
     * Update with optimistic locking
     */
    public boolean updateWithVersion(RecurringRuleDocument doc, long expectedVersion) {
        Query query = new Query();
        query.addCriteria(Criteria.where("_id").is(doc.getId()));
        query.addCriteria(Criteria.where("version").is(expectedVersion));

        Update update = new Update();
        update.set("name", doc.getName());
        update.set("description", doc.getDescription());
        update.set("baseAmount", doc.getBaseAmount());
        update.set("categoryName", doc.getCategoryName());
        update.set("recurrencePattern", doc.getRecurrencePattern());
        update.set("endDate", doc.getEndDate());
        update.set("status", doc.getStatus());
        update.set("amountChanges", doc.getAmountChanges());
        update.set("executions", doc.getExecutions());
        update.set("pauseInfo", doc.getPauseInfo());
        update.set("lastModifiedAt", ZonedDateTime.now());
        update.inc("version", 1);

        UpdateResult result = mongoTemplate.updateFirst(query, update, RecurringRuleDocument.class);
        return result.getModifiedCount() > 0;
    }

    /**
     * Add execution atomically
     */
    public void addExecution(String ruleId, LocalDate date, ExecutionDocument execution) {
        Query query = new Query(Criteria.where("_id").is(ruleId));
        Update update = new Update();
        update.set("executions." + date.toString(), execution);
        update.set("lastModifiedAt", ZonedDateTime.now());

        mongoTemplate.updateFirst(query, update, RecurringRuleDocument.class);
    }
}
```

---

## 7. Outbox Processor

### 7.1 OutboxRepository

```java
public interface OutboxRepository extends MongoRepository<OutboxDocument, UUID> {

    List<OutboxDocument> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    List<OutboxDocument> findByStatusAndNextRetryAtLessThanEqual(
            String status,
            ZonedDateTime now
    );

    @Modifying
    @Query("{ '_id': ?0, 'status': 'PENDING' }")
    int markAsProcessing(UUID id);
}
```

### 7.2 OutboxProcessor Service

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxProcessor {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${outbox.polling.interval:1000}")
    @Transactional
    public void processOutbox() {
        List<OutboxDocument> pendingEntries = outboxRepository
                .findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, 100));

        for (OutboxDocument entry : pendingEntries) {
            try {
                processEntry(entry);
            } catch (Exception e) {
                log.error("Failed to process outbox entry {}: {}", entry.getId(), e.getMessage());
                handleFailure(entry, e);
            }
        }
    }

    private void processEntry(OutboxDocument entry) throws Exception {
        String payload = objectMapper.writeValueAsString(entry.getPayload());

        kafkaTemplate.send(entry.getTargetTopic(), entry.getAggregateId(), payload)
                .get(10, TimeUnit.SECONDS);  // Wait for confirmation

        entry.setStatus("SENT");
        entry.setProcessedAt(ZonedDateTime.now());
        outboxRepository.save(entry);

        log.info("Successfully sent event {} for aggregate {}",
                entry.getEventType(), entry.getAggregateId());
    }

    private void handleFailure(OutboxDocument entry, Exception e) {
        entry.setRetryCount(entry.getRetryCount() + 1);
        entry.setLastError(e.getMessage());

        if (entry.getRetryCount() >= entry.getMaxRetries()) {
            entry.setStatus("FAILED");
            log.error("Outbox entry {} exceeded max retries, marked as FAILED", entry.getId());
        } else {
            // Exponential backoff
            long delaySeconds = (long) Math.pow(2, entry.getRetryCount()) * 60;
            entry.setNextRetryAt(ZonedDateTime.now().plusSeconds(delaySeconds));
        }

        outboxRepository.save(entry);
    }

    @Scheduled(fixedDelayString = "${outbox.retry.interval:60000}")
    public void retryFailedEntries() {
        List<OutboxDocument> retryableEntries = outboxRepository
                .findByStatusAndNextRetryAtLessThanEqual("FAILED", ZonedDateTime.now());

        for (OutboxDocument entry : retryableEntries) {
            entry.setStatus("PENDING");
            outboxRepository.save(entry);
        }
    }
}
```

---

## 8. Migration Scripts

### 8.1 Initial Collection Setup

```javascript
// Create collection with validation
db.createCollection("recurring_rules", {
  validator: {
    $jsonSchema: {
      // ... schema from section 2.3
    }
  }
});

// Create indexes
db.recurring_rules.createIndex(
  { cashFlowId: 1, status: 1, createdAt: -1 },
  { name: "idx_recurring_rules_cashflow_status_created" }
);

// ... other indexes from section 2.2

// Create sequence collection
db.recurring_rule_ids.insertOne({
  _id: "recurring_rule",
  sequence: NumberLong(10000000)
});

// Create outbox collection
db.createCollection("outbox");
db.outbox.createIndex(
  { status: 1, createdAt: 1 },
  { name: "idx_outbox_pending" }
);
// ... other outbox indexes
```

### 8.2 Data Migration (if upgrading)

```javascript
// Example: Add new field to existing documents
db.recurring_rules.updateMany(
  { version: { $exists: false } },
  { $set: { version: NumberLong(0) } }
);

// Example: Rename field
db.recurring_rules.updateMany(
  {},
  { $rename: { "amount": "baseAmount" } }
);
```

---

## Następny dokument

Przejdź do [05-bounded-context-integration.md](./05-bounded-context-integration.md) aby zobaczyć integrację bounded contexts.
