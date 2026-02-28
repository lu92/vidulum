package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.common.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a single execution of a recurring rule.
 * Tracks when the rule was executed, the resulting cash change, and status.
 */
public record RuleExecution(
        LocalDate executionDate,
        Instant executedAt,
        ExecutionStatus status,
        CashChangeId generatedCashChangeId,
        Money executedAmount,
        String failureReason
) {
    public RuleExecution {
        Objects.requireNonNull(executionDate, "Execution date cannot be null");
        Objects.requireNonNull(executedAt, "Executed at timestamp cannot be null");
        Objects.requireNonNull(status, "Execution status cannot be null");
    }

    public static RuleExecution success(
            LocalDate executionDate,
            Instant executedAt,
            CashChangeId generatedCashChangeId,
            Money executedAmount
    ) {
        return new RuleExecution(
                executionDate,
                executedAt,
                ExecutionStatus.SUCCESS,
                generatedCashChangeId,
                executedAmount,
                null
        );
    }

    public static RuleExecution failed(
            LocalDate executionDate,
            Instant executedAt,
            String failureReason
    ) {
        return new RuleExecution(
                executionDate,
                executedAt,
                ExecutionStatus.FAILED,
                null,
                null,
                failureReason
        );
    }

    public static RuleExecution skipped(
            LocalDate executionDate,
            Instant executedAt,
            String reason
    ) {
        return new RuleExecution(
                executionDate,
                executedAt,
                ExecutionStatus.SKIPPED,
                null,
                null,
                reason
        );
    }

    public boolean isSuccessful() {
        return status == ExecutionStatus.SUCCESS;
    }

    public Optional<CashChangeId> getGeneratedCashChangeId() {
        return Optional.ofNullable(generatedCashChangeId);
    }
}
