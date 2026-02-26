package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * RecurringRule aggregate - represents a recurring transaction rule that generates
 * expected cash changes in a CashFlow.
 * <p>
 * Supports different recurrence patterns: DAILY, WEEKLY, MONTHLY, YEARLY.
 * <p>
 * Lifecycle: ACTIVE → PAUSED → ACTIVE → COMPLETED/DELETED
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringRule implements Aggregate<RecurringRuleId, RecurringRuleSnapshot> {

    @Getter
    private RecurringRuleId ruleId;
    private UserId userId;
    private CashFlowId cashFlowId;
    private String name;
    private String description;
    private Money baseAmount;
    private CategoryName categoryName;
    private RecurrencePattern pattern;
    private LocalDate startDate;
    private LocalDate endDate;
    private RuleStatus status;
    private PauseInfo pauseInfo;
    private List<AmountChange> amountChanges;
    private List<RuleExecution> executions;
    private List<CashChangeId> generatedCashChangeIds;
    private Instant createdAt;
    private Instant lastModifiedAt;

    private Consumer<RecurringRuleEvent> eventConsumer;

    public static RecurringRule create(
            RecurringRuleId ruleId,
            UserId userId,
            CashFlowId cashFlowId,
            String name,
            String description,
            Money baseAmount,
            CategoryName categoryName,
            RecurrencePattern pattern,
            LocalDate startDate,
            LocalDate endDate,
            Clock clock
    ) {
        RecurringRule rule = new RecurringRule();
        rule.ruleId = ruleId;
        rule.userId = userId;
        rule.cashFlowId = cashFlowId;
        rule.name = name;
        rule.description = description;
        rule.baseAmount = baseAmount;
        rule.categoryName = categoryName;
        rule.pattern = pattern;
        rule.startDate = startDate;
        rule.endDate = endDate;
        rule.status = RuleStatus.ACTIVE;
        rule.pauseInfo = null;
        rule.amountChanges = new ArrayList<>();
        rule.executions = new ArrayList<>();
        rule.generatedCashChangeIds = new ArrayList<>();
        rule.createdAt = clock.instant();
        rule.lastModifiedAt = clock.instant();

        rule.emit(new RecurringRuleEvent.RuleCreated(
                ruleId,
                userId,
                cashFlowId,
                name,
                description,
                baseAmount,
                categoryName,
                pattern,
                startDate,
                endDate,
                clock.instant()
        ));

        return rule;
    }

    public static RecurringRule fromSnapshot(RecurringRuleSnapshot snapshot) {
        RecurringRule rule = new RecurringRule();
        rule.ruleId = snapshot.ruleId();
        rule.userId = snapshot.userId();
        rule.cashFlowId = snapshot.cashFlowId();
        rule.name = snapshot.name();
        rule.description = snapshot.description();
        rule.baseAmount = snapshot.baseAmount();
        rule.categoryName = snapshot.categoryName();
        rule.pattern = snapshot.pattern();
        rule.startDate = snapshot.startDate();
        rule.endDate = snapshot.endDate();
        rule.status = snapshot.status();
        rule.pauseInfo = snapshot.pauseInfo();
        rule.amountChanges = new ArrayList<>(snapshot.amountChanges());
        rule.executions = new ArrayList<>(snapshot.executions());
        rule.generatedCashChangeIds = new ArrayList<>(snapshot.generatedCashChangeIds());
        rule.createdAt = snapshot.createdAt();
        rule.lastModifiedAt = snapshot.lastModifiedAt();
        return rule;
    }

    @Override
    public RecurringRuleSnapshot getSnapshot() {
        return new RecurringRuleSnapshot(
                ruleId,
                userId,
                cashFlowId,
                name,
                description,
                baseAmount,
                categoryName,
                pattern,
                startDate,
                endDate,
                status,
                pauseInfo,
                List.copyOf(amountChanges),
                List.copyOf(executions),
                List.copyOf(generatedCashChangeIds),
                createdAt,
                lastModifiedAt
        );
    }

    public void registerEventConsumer(Consumer<RecurringRuleEvent> eventConsumer) {
        this.eventConsumer = eventConsumer;
    }

    private void emit(RecurringRuleEvent event) {
        if (eventConsumer != null) {
            eventConsumer.accept(event);
        }
    }

    public void update(
            String name,
            String description,
            Money baseAmount,
            CategoryName categoryName,
            RecurrencePattern pattern,
            LocalDate startDate,
            LocalDate endDate,
            Clock clock
    ) {
        this.name = name;
        this.description = description;
        this.baseAmount = baseAmount;
        this.categoryName = categoryName;
        this.pattern = pattern;
        this.startDate = startDate;
        this.endDate = endDate;
        this.lastModifiedAt = clock.instant();

        emit(new RecurringRuleEvent.RuleUpdated(
                ruleId,
                name,
                description,
                baseAmount,
                categoryName,
                pattern,
                startDate,
                endDate,
                clock.instant()
        ));
    }

    public void pause(PauseInfo pauseInfo, Clock clock) {
        if (status != RuleStatus.ACTIVE) {
            throw new IllegalStateException("Can only pause an active rule");
        }
        this.status = RuleStatus.PAUSED;
        this.pauseInfo = pauseInfo;
        this.lastModifiedAt = clock.instant();

        emit(new RecurringRuleEvent.RulePaused(ruleId, pauseInfo, clock.instant()));
    }

    public void resume(Clock clock) {
        if (status != RuleStatus.PAUSED) {
            throw new IllegalStateException("Can only resume a paused rule");
        }
        this.status = RuleStatus.ACTIVE;
        this.pauseInfo = null;
        this.lastModifiedAt = clock.instant();

        emit(new RecurringRuleEvent.RuleResumed(ruleId, clock.instant()));
    }

    public void complete(String reason, Clock clock) {
        if (status == RuleStatus.COMPLETED || status == RuleStatus.DELETED) {
            throw new IllegalStateException("Rule is already completed or deleted");
        }
        this.status = RuleStatus.COMPLETED;
        this.lastModifiedAt = clock.instant();

        emit(new RecurringRuleEvent.RuleCompleted(ruleId, reason, clock.instant()));
    }

    public void delete(String reason, Clock clock) {
        if (status == RuleStatus.DELETED) {
            throw new IllegalStateException("Rule is already deleted");
        }
        this.status = RuleStatus.DELETED;
        this.lastModifiedAt = clock.instant();

        emit(new RecurringRuleEvent.RuleDeleted(ruleId, reason, clock.instant()));
    }

    public void addAmountChange(AmountChange amountChange, Clock clock) {
        this.amountChanges.add(amountChange);
        this.lastModifiedAt = clock.instant();

        emit(new RecurringRuleEvent.AmountChangeAdded(ruleId, amountChange, clock.instant()));
    }

    public void removeAmountChange(AmountChangeId amountChangeId, Clock clock) {
        boolean removed = amountChanges.removeIf(ac -> ac.id().equals(amountChangeId));
        if (!removed) {
            throw new IllegalArgumentException("Amount change not found: " + amountChangeId.id());
        }
        this.lastModifiedAt = clock.instant();

        emit(new RecurringRuleEvent.AmountChangeRemoved(ruleId, amountChangeId, clock.instant()));
    }

    public void recordExecution(RuleExecution execution, Clock clock) {
        this.executions.add(execution);
        if (execution.isSuccessful() && execution.getGeneratedCashChangeId().isPresent()) {
            this.generatedCashChangeIds.add(execution.getGeneratedCashChangeId().get());
        }
        this.lastModifiedAt = clock.instant();

        emit(new RecurringRuleEvent.RuleExecuted(ruleId, execution, clock.instant()));
    }

    public void recordGeneratedCashChanges(
            List<CashChangeId> cashChangeIds,
            LocalDate fromDate,
            LocalDate toDate,
            Clock clock
    ) {
        this.generatedCashChangeIds.addAll(cashChangeIds);
        this.lastModifiedAt = clock.instant();

        emit(new RecurringRuleEvent.ExpectedCashChangesGenerated(
                ruleId,
                cashFlowId,
                cashChangeIds,
                fromDate,
                toDate,
                clock.instant()
        ));
    }

    public void clearGeneratedCashChanges(List<CashChangeId> clearedIds, Clock clock) {
        this.generatedCashChangeIds.removeAll(clearedIds);
        this.lastModifiedAt = clock.instant();

        emit(new RecurringRuleEvent.ExpectedCashChangesCleared(
                ruleId,
                cashFlowId,
                clearedIds,
                clock.instant()
        ));
    }

    /**
     * Calculates the effective amount for a given date, considering permanent amount changes.
     */
    public Money calculateEffectiveAmount(LocalDate forDate) {
        Money effective = baseAmount;

        for (AmountChange change : amountChanges) {
            if (change.type() == AmountChangeType.PERMANENT) {
                effective = change.amount();
            }
        }

        return effective;
    }

    /**
     * Gets the next one-time amount change, if any, and removes it.
     */
    public Optional<AmountChange> consumeOneTimeAmountChange() {
        for (int i = 0; i < amountChanges.size(); i++) {
            AmountChange change = amountChanges.get(i);
            if (change.type() == AmountChangeType.ONE_TIME) {
                amountChanges.remove(i);
                return Optional.of(change);
            }
        }
        return Optional.empty();
    }

    /**
     * Generates all occurrence dates within the specified date range.
     */
    public List<LocalDate> generateOccurrences(LocalDate fromDate, LocalDate toDate) {
        List<LocalDate> occurrences = new ArrayList<>();

        LocalDate effectiveStart = startDate.isAfter(fromDate) ? startDate : fromDate;
        LocalDate effectiveEnd = (endDate != null && endDate.isBefore(toDate)) ? endDate : toDate;

        LocalDate current = pattern.nextOccurrenceFrom(effectiveStart);

        while (!current.isAfter(effectiveEnd)) {
            occurrences.add(current);
            current = pattern.nextOccurrenceFrom(current.plusDays(1));
        }

        return occurrences;
    }

    public boolean isActive() {
        return status == RuleStatus.ACTIVE;
    }

    public boolean isPaused() {
        return status == RuleStatus.PAUSED;
    }

    public boolean isCompleted() {
        return status == RuleStatus.COMPLETED;
    }

    public boolean isDeleted() {
        return status == RuleStatus.DELETED;
    }

    public boolean shouldExecuteOn(LocalDate date) {
        if (!isActive()) {
            return false;
        }
        if (date.isBefore(startDate)) {
            return false;
        }
        if (endDate != null && date.isAfter(endDate)) {
            return false;
        }
        return pattern.isValidForDate(date);
    }

    // Getters for read-only access
    public UserId getUserId() {
        return userId;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Money getBaseAmount() {
        return baseAmount;
    }

    public CategoryName getCategoryName() {
        return categoryName;
    }

    public RecurrencePattern getPattern() {
        return pattern;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public Optional<LocalDate> getEndDate() {
        return Optional.ofNullable(endDate);
    }

    public RuleStatus getStatus() {
        return status;
    }

    public Optional<PauseInfo> getPauseInfo() {
        return Optional.ofNullable(pauseInfo);
    }

    public List<AmountChange> getAmountChanges() {
        return List.copyOf(amountChanges);
    }

    public List<RuleExecution> getExecutions() {
        return List.copyOf(executions);
    }

    public List<CashChangeId> getGeneratedCashChangeIds() {
        return List.copyOf(generatedCashChangeIds);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }
}
