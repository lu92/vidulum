package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Sealed interface for all recurring rule domain events.
 */
public sealed interface RecurringRuleEvent {

    RecurringRuleId ruleId();

    Instant occurredAt();

    record RuleCreated(
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
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }

    record RuleUpdated(
            RecurringRuleId ruleId,
            String name,
            String description,
            Money baseAmount,
            CategoryName categoryName,
            RecurrencePattern pattern,
            LocalDate startDate,
            LocalDate endDate,
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }

    record RulePaused(
            RecurringRuleId ruleId,
            PauseInfo pauseInfo,
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }

    record RuleResumed(
            RecurringRuleId ruleId,
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }

    record RuleCompleted(
            RecurringRuleId ruleId,
            String reason,
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }

    record RuleDeleted(
            RecurringRuleId ruleId,
            String reason,
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }

    record AmountChangeAdded(
            RecurringRuleId ruleId,
            AmountChange amountChange,
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }

    record AmountChangeRemoved(
            RecurringRuleId ruleId,
            AmountChangeId amountChangeId,
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }

    record RuleExecuted(
            RecurringRuleId ruleId,
            RuleExecution execution,
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }

    record ExpectedCashChangesGenerated(
            RecurringRuleId ruleId,
            CashFlowId cashFlowId,
            List<CashChangeId> generatedCashChangeIds,
            LocalDate fromDate,
            LocalDate toDate,
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }

    record ExpectedCashChangesCleared(
            RecurringRuleId ruleId,
            CashFlowId cashFlowId,
            List<CashChangeId> clearedCashChangeIds,
            Instant occurredAt
    ) implements RecurringRuleEvent {
    }
}
