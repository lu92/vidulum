package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Snapshot of a RecurringRule aggregate for persistence.
 */
public record RecurringRuleSnapshot(
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
        RuleStatus status,
        PauseInfo pauseInfo,
        List<AmountChange> amountChanges,
        List<RuleExecution> executions,
        List<CashChangeId> generatedCashChangeIds,
        Instant createdAt,
        Instant lastModifiedAt
) implements EntitySnapshot<RecurringRuleId> {
    @Override
    public RecurringRuleId id() {
        return ruleId;
    }
}
