# Domain Model - Recurring Rules

**Powiązane:** [01-rest-api-design.md](./01-rest-api-design.md) | [Następny: 03-user-journeys.md](./03-user-journeys.md)

---

## 1. Agregat RecurringRule

### 1.1 Diagram klas

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         <<Aggregate Root>>                              │
│                          RecurringRule                                  │
├─────────────────────────────────────────────────────────────────────────┤
│ - ruleId: RecurringRuleId                                               │
│ - cashFlowId: CashFlowId                                                │
│ - userId: UserId                                                        │
│ - name: Name                                                            │
│ - description: Description                                              │
│ - baseAmount: Money                                                     │
│ - type: Type (INFLOW/OUTFLOW)                                          │
│ - categoryName: CategoryName                                            │
│ - recurrencePattern: RecurrencePattern                                  │
│ - startDate: LocalDate                                                  │
│ - endDate: LocalDate (nullable)                                         │
│ - status: RuleStatus                                                    │
│ - amountChanges: Map<AmountChangeId, AmountChange>                      │
│ - executions: Map<LocalDate, RuleExecution>                             │
│ - pauseInfo: PauseInfo (nullable)                                       │
│ - version: long                                                         │
│ - createdAt: ZonedDateTime                                              │
│ - lastModifiedAt: ZonedDateTime                                         │
│ - uncommittedEvents: List<RecurringRuleEvent>                           │
├─────────────────────────────────────────────────────────────────────────┤
│ + create(command): RecurringRule                                        │
│ + update(command): void                                                 │
│ + delete(deleteTransactions): void                                      │
│ + pause(reason, resumeDate): void                                       │
│ + resume(generateSkipped): void                                         │
│ + addAmountChange(change): void                                         │
│ + removeAmountChange(changeId): void                                    │
│ + recordExecution(date, status, cashChangeId): void                     │
│ + getEffectiveAmount(date): Money                                       │
│ + calculateNextOccurrence(): LocalDate                                  │
│ + getUpcomingOccurrences(months): List<LocalDate>                       │
│ + apply(event): void                                                    │
│ + getSnapshot(): RecurringRuleSnapshot                                  │
│ + fromSnapshot(snapshot): RecurringRule                                 │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ contains
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
        ┌───────────────────┐ ┌─────────────┐ ┌─────────────────┐
        │   AmountChange    │ │RuleExecution│ │RecurrencePattern│
        ├───────────────────┤ ├─────────────┤ ├─────────────────┤
        │ - changeId        │ │ - date      │ │ <<sealed>>      │
        │ - effectiveDate   │ │ - status    │ │ + nextOccurrence│
        │ - type            │ │ - executedAt│ │ + isValidFor    │
        │ - newAmount       │ │ - cashChange│ │                 │
        │ - reason          │ │ - error     │ └────────┬────────┘
        │ - createdAt       │ └─────────────┘          │
        └───────────────────┘                ┌─────────┴─────────┐
                                             │                   │
                                    ┌────────┴────────┐ ┌────────┴────────┐
                                    │  DailyPattern   │ │  WeeklyPattern  │
                                    ├─────────────────┤ ├─────────────────┤
                                    │ - intervalDays  │ │ - dayOfWeek     │
                                    └─────────────────┘ │ - intervalWeeks │
                                                        └─────────────────┘
                                    ┌─────────────────┐ ┌─────────────────┐
                                    │ MonthlyPattern  │ │  YearlyPattern  │
                                    ├─────────────────┤ ├─────────────────┤
                                    │ - dayOfMonth    │ │ - month         │
                                    │ - intervalMonths│ │ - dayOfMonth    │
                                    │ - adjustForEnd  │ └─────────────────┘
                                    └─────────────────┘
```

### 1.2 Implementacja agregatu

```java
package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RecurringRule implements Aggregate<RecurringRuleId, RecurringRuleSnapshot> {

    private RecurringRuleId ruleId;
    private CashFlowId cashFlowId;
    private UserId userId;
    private Name name;
    private Description description;
    private Money baseAmount;
    private Type type;
    private CategoryName categoryName;
    private RecurrencePattern recurrencePattern;
    private LocalDate startDate;
    private LocalDate endDate;
    private RuleStatus status;
    private Map<AmountChangeId, AmountChange> amountChanges;
    private Map<LocalDate, RuleExecution> executions;
    private PauseInfo pauseInfo;
    private long version;
    private ZonedDateTime createdAt;
    private ZonedDateTime lastModifiedAt;

    private final List<RecurringRuleEvent> uncommittedEvents = new LinkedList<>();

    // ==================== Factory Methods ====================

    public static RecurringRule create(
            RecurringRuleId ruleId,
            CashFlowId cashFlowId,
            UserId userId,
            Name name,
            Description description,
            Money amount,
            Type type,
            CategoryName categoryName,
            RecurrencePattern recurrencePattern,
            LocalDate startDate,
            LocalDate endDate,
            ZonedDateTime now
    ) {
        // Validations
        Objects.requireNonNull(ruleId, "Rule ID cannot be null");
        Objects.requireNonNull(cashFlowId, "CashFlow ID cannot be null");
        Objects.requireNonNull(userId, "User ID cannot be null");
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(categoryName, "Category name cannot be null");
        Objects.requireNonNull(recurrencePattern, "Recurrence pattern cannot be null");
        Objects.requireNonNull(startDate, "Start date cannot be null");

        if (endDate != null && !endDate.isAfter(startDate)) {
            throw new IllegalArgumentException("End date must be after start date");
        }

        RecurringRule rule = new RecurringRule();
        rule.apply(new RecurringRuleEvent.RuleCreatedEvent(
                ruleId, cashFlowId, userId, name, description,
                amount, type, categoryName, recurrencePattern,
                startDate, endDate, now
        ));
        return rule;
    }

    public static RecurringRule from(RecurringRuleSnapshot snapshot) {
        RecurringRule rule = new RecurringRule();
        rule.ruleId = snapshot.ruleId();
        rule.cashFlowId = snapshot.cashFlowId();
        rule.userId = snapshot.userId();
        rule.name = snapshot.name();
        rule.description = snapshot.description();
        rule.baseAmount = snapshot.baseAmount();
        rule.type = snapshot.type();
        rule.categoryName = snapshot.categoryName();
        rule.recurrencePattern = snapshot.recurrencePattern();
        rule.startDate = snapshot.startDate();
        rule.endDate = snapshot.endDate();
        rule.status = snapshot.status();
        rule.amountChanges = new HashMap<>(snapshot.amountChanges());
        rule.executions = new HashMap<>(snapshot.executions());
        rule.pauseInfo = snapshot.pauseInfo();
        rule.version = snapshot.version();
        rule.createdAt = snapshot.createdAt();
        rule.lastModifiedAt = snapshot.lastModifiedAt();
        return rule;
    }

    @Override
    public RecurringRuleSnapshot getSnapshot() {
        return new RecurringRuleSnapshot(
                ruleId, cashFlowId, userId, name, description,
                baseAmount, type, categoryName, recurrencePattern,
                startDate, endDate, status,
                Map.copyOf(amountChanges), Map.copyOf(executions),
                pauseInfo, version, createdAt, lastModifiedAt
        );
    }

    // ==================== Commands ====================

    public void update(
            Name newName,
            Description newDescription,
            Money newAmount,
            CategoryName newCategoryName,
            RecurrencePattern newPattern,
            LocalDate newEndDate,
            boolean applyToFutureOnly,
            ZonedDateTime now
    ) {
        ensureNotDeleted();

        apply(new RecurringRuleEvent.RuleUpdatedEvent(
                ruleId,
                newName != null ? newName : name,
                newDescription != null ? newDescription : description,
                newAmount != null ? newAmount : baseAmount,
                newCategoryName != null ? newCategoryName : categoryName,
                newPattern != null ? newPattern : recurrencePattern,
                newEndDate,  // null means remove end date
                applyToFutureOnly,
                now
        ));
    }

    public void delete(boolean deleteGeneratedTransactions, ZonedDateTime now) {
        ensureNotDeleted();

        apply(new RecurringRuleEvent.RuleDeletedEvent(
                ruleId, deleteGeneratedTransactions, now
        ));
    }

    public void pause(String reason, LocalDate resumeDate, ZonedDateTime now) {
        ensureActive();

        apply(new RecurringRuleEvent.RulePausedEvent(
                ruleId, reason, resumeDate, now
        ));
    }

    public void resume(boolean generateSkipped, ZonedDateTime now) {
        ensurePaused();

        apply(new RecurringRuleEvent.RuleResumedEvent(
                ruleId, generateSkipped, now
        ));
    }

    public void addAmountChange(
            AmountChangeId changeId,
            LocalDate effectiveDate,
            AmountChangeType changeType,
            Money newAmount,
            String reason,
            ZonedDateTime now
    ) {
        ensureNotDeleted();
        validateAmountChangeDate(effectiveDate);
        validateNoCurrencyMismatch(newAmount);

        apply(new RecurringRuleEvent.AmountChangeAddedEvent(
                ruleId, changeId, effectiveDate, changeType, newAmount, reason, now
        ));
    }

    public void removeAmountChange(AmountChangeId changeId, ZonedDateTime now) {
        ensureNotDeleted();

        if (!amountChanges.containsKey(changeId)) {
            throw new AmountChangeNotFoundException(changeId);
        }

        apply(new RecurringRuleEvent.AmountChangeRemovedEvent(ruleId, changeId, now));
    }

    public void recordExecution(
            LocalDate scheduledDate,
            ExecutionStatus executionStatus,
            CashChangeId generatedCashChangeId,
            String errorMessage,
            ZonedDateTime executedAt
    ) {
        apply(new RecurringRuleEvent.ExecutionRecordedEvent(
                ruleId, scheduledDate, executionStatus,
                generatedCashChangeId, errorMessage, executedAt
        ));
    }

    public void updateCategoryName(CategoryName newCategoryName, ZonedDateTime now) {
        // Called when category is renamed in CashFlow
        if (!categoryName.equals(newCategoryName)) {
            apply(new RecurringRuleEvent.CategorySyncedEvent(
                    ruleId, categoryName, newCategoryName, now
            ));
        }
    }

    public void handleCategoryArchived(ZonedDateTime now) {
        // Called when category is archived in CashFlow
        apply(new RecurringRuleEvent.RulePausedEvent(
                ruleId,
                "Category '" + categoryName.name() + "' was archived",
                null,
                now
        ));
    }

    // ==================== Queries ====================

    public Money getEffectiveAmount(LocalDate date) {
        // Find applicable amount changes
        Money effectiveAmount = baseAmount;

        // Sort changes by effective date
        List<AmountChange> sortedChanges = amountChanges.values().stream()
                .sorted(Comparator.comparing(AmountChange::effectiveDate))
                .toList();

        for (AmountChange change : sortedChanges) {
            if (!change.effectiveDate().isAfter(date)) {
                if (change.type() == AmountChangeType.PERMANENT) {
                    effectiveAmount = change.newAmount();
                } else if (change.type() == AmountChangeType.ONE_TIME
                        && change.effectiveDate().equals(date)) {
                    effectiveAmount = change.newAmount();
                }
            }
        }

        return effectiveAmount;
    }

    public Optional<LocalDate> calculateNextOccurrence() {
        return calculateNextOccurrenceFrom(LocalDate.now());
    }

    public Optional<LocalDate> calculateNextOccurrenceFrom(LocalDate fromDate) {
        if (status != RuleStatus.ACTIVE) {
            return Optional.empty();
        }

        LocalDate candidate = recurrencePattern.nextOccurrenceFrom(
                fromDate.isBefore(startDate) ? startDate : fromDate
        );

        if (endDate != null && candidate.isAfter(endDate)) {
            return Optional.empty();
        }

        return Optional.of(candidate);
    }

    public List<LocalDate> getUpcomingOccurrences(int months) {
        List<LocalDate> occurrences = new ArrayList<>();
        LocalDate current = LocalDate.now();
        LocalDate limit = current.plusMonths(months);

        while (current.isBefore(limit)) {
            Optional<LocalDate> next = calculateNextOccurrenceFrom(current);
            if (next.isEmpty() || next.get().isAfter(limit)) {
                break;
            }
            occurrences.add(next.get());
            current = next.get().plusDays(1);
        }

        return occurrences;
    }

    public boolean isExecutedFor(LocalDate date) {
        return executions.containsKey(date) &&
                executions.get(date).status() == ExecutionStatus.SUCCESS;
    }

    public List<RecurringRuleEvent> getUncommittedEvents() {
        return List.copyOf(uncommittedEvents);
    }

    public void markEventsAsCommitted() {
        uncommittedEvents.clear();
    }

    // ==================== Event Handlers (apply methods) ====================

    public void apply(RecurringRuleEvent event) {
        switch (event) {
            case RecurringRuleEvent.RuleCreatedEvent e -> handleRuleCreated(e);
            case RecurringRuleEvent.RuleUpdatedEvent e -> handleRuleUpdated(e);
            case RecurringRuleEvent.RuleDeletedEvent e -> handleRuleDeleted(e);
            case RecurringRuleEvent.RulePausedEvent e -> handleRulePaused(e);
            case RecurringRuleEvent.RuleResumedEvent e -> handleRuleResumed(e);
            case RecurringRuleEvent.AmountChangeAddedEvent e -> handleAmountChangeAdded(e);
            case RecurringRuleEvent.AmountChangeRemovedEvent e -> handleAmountChangeRemoved(e);
            case RecurringRuleEvent.ExecutionRecordedEvent e -> handleExecutionRecorded(e);
            case RecurringRuleEvent.CategorySyncedEvent e -> handleCategorySynced(e);
        }
        uncommittedEvents.add(event);
    }

    private void handleRuleCreated(RecurringRuleEvent.RuleCreatedEvent event) {
        this.ruleId = event.ruleId();
        this.cashFlowId = event.cashFlowId();
        this.userId = event.userId();
        this.name = event.name();
        this.description = event.description();
        this.baseAmount = event.amount();
        this.type = event.type();
        this.categoryName = event.categoryName();
        this.recurrencePattern = event.recurrencePattern();
        this.startDate = event.startDate();
        this.endDate = event.endDate();
        this.status = RuleStatus.ACTIVE;
        this.amountChanges = new HashMap<>();
        this.executions = new HashMap<>();
        this.pauseInfo = null;
        this.version = 0;
        this.createdAt = event.createdAt();
        this.lastModifiedAt = event.createdAt();
    }

    private void handleRuleUpdated(RecurringRuleEvent.RuleUpdatedEvent event) {
        this.name = event.name();
        this.description = event.description();
        this.baseAmount = event.amount();
        this.categoryName = event.categoryName();
        this.recurrencePattern = event.recurrencePattern();
        this.endDate = event.endDate();
        this.lastModifiedAt = event.updatedAt();
        this.version++;
    }

    private void handleRuleDeleted(RecurringRuleEvent.RuleDeletedEvent event) {
        this.status = RuleStatus.DELETED;
        this.lastModifiedAt = event.deletedAt();
        this.version++;
    }

    private void handleRulePaused(RecurringRuleEvent.RulePausedEvent event) {
        this.status = RuleStatus.PAUSED;
        this.pauseInfo = new PauseInfo(event.reason(), event.pausedAt(), event.resumeDate());
        this.lastModifiedAt = event.pausedAt();
        this.version++;
    }

    private void handleRuleResumed(RecurringRuleEvent.RuleResumedEvent event) {
        this.status = RuleStatus.ACTIVE;
        this.pauseInfo = null;
        this.lastModifiedAt = event.resumedAt();
        this.version++;
    }

    private void handleAmountChangeAdded(RecurringRuleEvent.AmountChangeAddedEvent event) {
        AmountChange change = new AmountChange(
                event.changeId(),
                event.effectiveDate(),
                event.changeType(),
                event.newAmount(),
                event.reason(),
                event.addedAt()
        );
        this.amountChanges.put(event.changeId(), change);
        this.lastModifiedAt = event.addedAt();
        this.version++;
    }

    private void handleAmountChangeRemoved(RecurringRuleEvent.AmountChangeRemovedEvent event) {
        this.amountChanges.remove(event.changeId());
        this.lastModifiedAt = event.removedAt();
        this.version++;
    }

    private void handleExecutionRecorded(RecurringRuleEvent.ExecutionRecordedEvent event) {
        RuleExecution execution = new RuleExecution(
                event.scheduledDate(),
                event.status(),
                event.executedAt(),
                event.generatedCashChangeId(),
                event.errorMessage()
        );
        this.executions.put(event.scheduledDate(), execution);
        this.lastModifiedAt = event.executedAt();
    }

    private void handleCategorySynced(RecurringRuleEvent.CategorySyncedEvent event) {
        this.categoryName = event.newCategoryName();
        this.lastModifiedAt = event.syncedAt();
        this.version++;
    }

    // ==================== Validation Helpers ====================

    private void ensureNotDeleted() {
        if (status == RuleStatus.DELETED) {
            throw new RuleAlreadyDeletedException(ruleId);
        }
    }

    private void ensureActive() {
        if (status != RuleStatus.ACTIVE) {
            throw new InvalidRuleStatusException(ruleId, status, RuleStatus.ACTIVE);
        }
    }

    private void ensurePaused() {
        if (status != RuleStatus.PAUSED) {
            throw new InvalidRuleStatusException(ruleId, status, RuleStatus.PAUSED);
        }
    }

    private void validateAmountChangeDate(LocalDate effectiveDate) {
        if (effectiveDate.isBefore(startDate)) {
            throw new InvalidAmountChangeDateException(
                    "Effective date cannot be before rule start date"
            );
        }
        if (endDate != null && effectiveDate.isAfter(endDate)) {
            throw new InvalidAmountChangeDateException(
                    "Effective date cannot be after rule end date"
            );
        }
    }

    private void validateNoCurrencyMismatch(Money newAmount) {
        if (!newAmount.currency().equals(baseAmount.currency())) {
            throw new CurrencyMismatchException(
                    baseAmount.currency(), newAmount.currency()
            );
        }
    }
}
```

---

## 2. Value Objects

### 2.1 RecurringRuleId
```java
package com.multi.vidulum.recurring_rules.domain;

public record RecurringRuleId(String id) {

    private static final String PREFIX = "RR";

    public RecurringRuleId {
        Objects.requireNonNull(id, "Rule ID cannot be null");
        if (!id.matches(PREFIX + "\\d+")) {
            throw new IllegalArgumentException("Invalid rule ID format: " + id);
        }
    }

    public static RecurringRuleId generate(long sequence) {
        return new RecurringRuleId(PREFIX + String.format("%08d", sequence));
    }

    @Override
    public String toString() {
        return id;
    }
}
```

### 2.2 AmountChangeId
```java
public record AmountChangeId(String id) {

    private static final String PREFIX = "AC";

    public AmountChangeId {
        Objects.requireNonNull(id, "Amount change ID cannot be null");
        if (!id.matches(PREFIX + "\\d+")) {
            throw new IllegalArgumentException("Invalid amount change ID format: " + id);
        }
    }

    public static AmountChangeId generate(long sequence) {
        return new AmountChangeId(PREFIX + String.format("%08d", sequence));
    }
}
```

### 2.3 RecurrencePattern (sealed interface)
```java
package com.multi.vidulum.recurring_rules.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

public sealed interface RecurrencePattern
        permits DailyPattern, WeeklyPattern, MonthlyPattern, YearlyPattern {

    RecurrenceType type();

    LocalDate nextOccurrenceFrom(LocalDate fromDate);

    boolean isValidForDate(LocalDate date);

    String toDisplayString();
}

// DAILY
public record DailyPattern(int intervalDays) implements RecurrencePattern {

    public DailyPattern {
        if (intervalDays < 1 || intervalDays > 365) {
            throw new IllegalArgumentException("Interval must be between 1 and 365 days");
        }
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.DAILY;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        return fromDate;  // Daily starts from the given date
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        return true;  // Every day is valid for daily pattern
    }

    @Override
    public String toDisplayString() {
        return intervalDays == 1 ? "Every day" : "Every " + intervalDays + " days";
    }
}

// WEEKLY
public record WeeklyPattern(DayOfWeek dayOfWeek, int intervalWeeks) implements RecurrencePattern {

    public WeeklyPattern {
        Objects.requireNonNull(dayOfWeek);
        if (intervalWeeks < 1 || intervalWeeks > 52) {
            throw new IllegalArgumentException("Interval must be between 1 and 52 weeks");
        }
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.WEEKLY;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        LocalDate candidate = fromDate;
        while (candidate.getDayOfWeek() != dayOfWeek) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        return date.getDayOfWeek() == dayOfWeek;
    }

    @Override
    public String toDisplayString() {
        String dayName = dayOfWeek.toString().charAt(0) +
                dayOfWeek.toString().substring(1).toLowerCase();
        return intervalWeeks == 1
                ? "Every " + dayName
                : "Every " + intervalWeeks + " weeks on " + dayName;
    }
}

// MONTHLY
public record MonthlyPattern(
        int dayOfMonth,
        int intervalMonths,
        boolean adjustForMonthEnd
) implements RecurrencePattern {

    public MonthlyPattern {
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            throw new IllegalArgumentException("Day of month must be between 1 and 31");
        }
        if (intervalMonths < 1 || intervalMonths > 12) {
            throw new IllegalArgumentException("Interval must be between 1 and 12 months");
        }
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.MONTHLY;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        int targetDay = Math.min(dayOfMonth, fromDate.lengthOfMonth());

        if (adjustForMonthEnd && dayOfMonth > fromDate.lengthOfMonth()) {
            targetDay = fromDate.lengthOfMonth();
        }

        LocalDate candidate = fromDate.withDayOfMonth(targetDay);
        if (candidate.isBefore(fromDate)) {
            candidate = candidate.plusMonths(intervalMonths);
            targetDay = Math.min(dayOfMonth, candidate.lengthOfMonth());
            candidate = candidate.withDayOfMonth(targetDay);
        }

        return candidate;
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        int expectedDay = Math.min(dayOfMonth, date.lengthOfMonth());
        return date.getDayOfMonth() == expectedDay;
    }

    @Override
    public String toDisplayString() {
        String daySuffix = getDaySuffix(dayOfMonth);
        return intervalMonths == 1
                ? "Monthly on the " + dayOfMonth + daySuffix
                : "Every " + intervalMonths + " months on the " + dayOfMonth + daySuffix;
    }

    private static String getDaySuffix(int day) {
        if (day >= 11 && day <= 13) return "th";
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }
}

// YEARLY
public record YearlyPattern(int month, int dayOfMonth) implements RecurrencePattern {

    public YearlyPattern {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Month must be between 1 and 12");
        }
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            throw new IllegalArgumentException("Day of month must be between 1 and 31");
        }
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.YEARLY;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        LocalDate candidate = LocalDate.of(fromDate.getYear(), month, 1);
        int targetDay = Math.min(dayOfMonth, candidate.lengthOfMonth());
        candidate = candidate.withDayOfMonth(targetDay);

        if (candidate.isBefore(fromDate)) {
            candidate = LocalDate.of(fromDate.getYear() + 1, month, 1);
            targetDay = Math.min(dayOfMonth, candidate.lengthOfMonth());
            candidate = candidate.withDayOfMonth(targetDay);
        }

        return candidate;
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        return date.getMonthValue() == month &&
                date.getDayOfMonth() == Math.min(dayOfMonth, date.lengthOfMonth());
    }

    @Override
    public String toDisplayString() {
        String monthName = java.time.Month.of(month).toString();
        monthName = monthName.charAt(0) + monthName.substring(1).toLowerCase();
        return "Yearly on " + monthName + " " + dayOfMonth;
    }
}
```

### 2.4 Entities

```java
// AmountChange - embedded entity
public record AmountChange(
        AmountChangeId changeId,
        LocalDate effectiveDate,
        AmountChangeType type,
        Money newAmount,
        String reason,
        ZonedDateTime createdAt
) {}

// RuleExecution - embedded entity
public record RuleExecution(
        LocalDate scheduledDate,
        ExecutionStatus status,
        ZonedDateTime executedAt,
        CashChangeId generatedCashChangeId,  // nullable if failed
        String errorMessage                   // nullable if success
) {}

// PauseInfo - value object
public record PauseInfo(
        String reason,
        ZonedDateTime pausedAt,
        LocalDate scheduledResumeDate  // nullable
) {}
```

---

## 3. Enums

```java
public enum RuleStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    DELETED
}

public enum RecurrenceType {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}

public enum AmountChangeType {
    ONE_TIME,    // Tylko dla konkretnej daty
    PERMANENT    // Od tej daty na zawsze
}

public enum ExecutionStatus {
    SUCCESS,     // Transakcja wygenerowana pomyślnie
    FAILED,      // Błąd podczas generowania
    SKIPPED      // Pominięte (np. reguła była wstrzymana)
}
```

---

## 4. Domain Events

```java
package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.time.LocalDate;
import java.time.ZonedDateTime;

public sealed interface RecurringRuleEvent extends DomainEvent
        permits
        RecurringRuleEvent.RuleCreatedEvent,
        RecurringRuleEvent.RuleUpdatedEvent,
        RecurringRuleEvent.RuleDeletedEvent,
        RecurringRuleEvent.RulePausedEvent,
        RecurringRuleEvent.RuleResumedEvent,
        RecurringRuleEvent.AmountChangeAddedEvent,
        RecurringRuleEvent.AmountChangeRemovedEvent,
        RecurringRuleEvent.ExecutionRecordedEvent,
        RecurringRuleEvent.CategorySyncedEvent {

    RecurringRuleId ruleId();

    ZonedDateTime occurredAt();

    // ==================== Events ====================

    record RuleCreatedEvent(
            RecurringRuleId ruleId,
            CashFlowId cashFlowId,
            UserId userId,
            Name name,
            Description description,
            Money amount,
            Type type,
            CategoryName categoryName,
            RecurrencePattern recurrencePattern,
            LocalDate startDate,
            LocalDate endDate,
            ZonedDateTime createdAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return createdAt;
        }
    }

    record RuleUpdatedEvent(
            RecurringRuleId ruleId,
            Name name,
            Description description,
            Money amount,
            CategoryName categoryName,
            RecurrencePattern recurrencePattern,
            LocalDate endDate,
            boolean applyToFutureOnly,
            ZonedDateTime updatedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return updatedAt;
        }
    }

    record RuleDeletedEvent(
            RecurringRuleId ruleId,
            boolean deleteGeneratedTransactions,
            ZonedDateTime deletedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return deletedAt;
        }
    }

    record RulePausedEvent(
            RecurringRuleId ruleId,
            String reason,
            LocalDate resumeDate,
            ZonedDateTime pausedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return pausedAt;
        }
    }

    record RuleResumedEvent(
            RecurringRuleId ruleId,
            boolean generateSkipped,
            ZonedDateTime resumedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return resumedAt;
        }
    }

    record AmountChangeAddedEvent(
            RecurringRuleId ruleId,
            AmountChangeId changeId,
            LocalDate effectiveDate,
            AmountChangeType changeType,
            Money newAmount,
            String reason,
            ZonedDateTime addedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return addedAt;
        }
    }

    record AmountChangeRemovedEvent(
            RecurringRuleId ruleId,
            AmountChangeId changeId,
            ZonedDateTime removedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return removedAt;
        }
    }

    record ExecutionRecordedEvent(
            RecurringRuleId ruleId,
            LocalDate scheduledDate,
            ExecutionStatus status,
            CashChangeId generatedCashChangeId,
            String errorMessage,
            ZonedDateTime executedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return executedAt;
        }
    }

    record CategorySyncedEvent(
            RecurringRuleId ruleId,
            CategoryName oldCategoryName,
            CategoryName newCategoryName,
            ZonedDateTime syncedAt
    ) implements RecurringRuleEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return syncedAt;
        }
    }
}
```

---

## 5. Snapshot dla persystencji

```java
public record RecurringRuleSnapshot(
        RecurringRuleId ruleId,
        CashFlowId cashFlowId,
        UserId userId,
        Name name,
        Description description,
        Money baseAmount,
        Type type,
        CategoryName categoryName,
        RecurrencePattern recurrencePattern,
        LocalDate startDate,
        LocalDate endDate,
        RuleStatus status,
        Map<AmountChangeId, AmountChange> amountChanges,
        Map<LocalDate, RuleExecution> executions,
        PauseInfo pauseInfo,
        long version,
        ZonedDateTime createdAt,
        ZonedDateTime lastModifiedAt
) {}
```

---

## 6. Domain Exceptions

```java
// Base exception
public abstract class RecurringRuleException extends RuntimeException {
    private final String errorCode;

    protected RecurringRuleException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

// Specific exceptions
public class RecurringRuleNotFoundException extends RecurringRuleException {
    public RecurringRuleNotFoundException(RecurringRuleId ruleId) {
        super("RR101", "Recurring rule '" + ruleId + "' not found");
    }
}

public class RuleAlreadyDeletedException extends RecurringRuleException {
    public RuleAlreadyDeletedException(RecurringRuleId ruleId) {
        super("RR102", "Cannot modify deleted rule '" + ruleId + "'");
    }
}

public class InvalidRuleStatusException extends RecurringRuleException {
    public InvalidRuleStatusException(RecurringRuleId ruleId, RuleStatus current, RuleStatus required) {
        super("RR103", "Rule '" + ruleId + "' is in status " + current +
                ", but " + required + " is required");
    }
}

public class AmountChangeNotFoundException extends RecurringRuleException {
    public AmountChangeNotFoundException(AmountChangeId changeId) {
        super("RR201", "Amount change '" + changeId + "' not found");
    }
}

public class InvalidAmountChangeDateException extends RecurringRuleException {
    public InvalidAmountChangeDateException(String message) {
        super("RR202", message);
    }
}

public class CurrencyMismatchException extends RecurringRuleException {
    public CurrencyMismatchException(String expected, String actual) {
        super("RR203", "Currency mismatch: rule uses " + expected +
                ", but " + actual + " was provided");
    }
}

public class CategoryValidationException extends RecurringRuleException {
    public CategoryValidationException(String errorCode, String message) {
        super(errorCode, message);
    }

    public static CategoryValidationException notFound(String categoryName, CashFlowId cashFlowId) {
        return new CategoryValidationException("RR004",
                "Category '" + categoryName + "' not found in CashFlow '" + cashFlowId + "'");
    }

    public static CategoryValidationException archived(String categoryName) {
        return new CategoryValidationException("RR005",
                "Category '" + categoryName + "' is archived");
    }

    public static CategoryValidationException typeMismatch(String categoryName, Type ruleType, Type categoryType) {
        return new CategoryValidationException("RR006",
                "Category '" + categoryName + "' is of type " + categoryType +
                        ", but rule is of type " + ruleType);
    }
}

public class CashFlowServiceUnavailableException extends RecurringRuleException {
    public CashFlowServiceUnavailableException(String message) {
        super("RR503", message);
    }
}
```

---

## Następny dokument

Przejdź do [03-user-journeys.md](./03-user-journeys.md) aby zobaczyć szczegółowe user journeys.
