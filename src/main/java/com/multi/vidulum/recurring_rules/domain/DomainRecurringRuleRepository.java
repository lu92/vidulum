package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.UserId;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Domain repository interface for RecurringRule aggregate.
 */
public interface DomainRecurringRuleRepository {

    void save(RecurringRule rule);

    Optional<RecurringRule> findById(RecurringRuleId ruleId);

    List<RecurringRule> findByCashFlowId(CashFlowId cashFlowId);

    List<RecurringRule> findByUserId(UserId userId);

    List<RecurringRule> findActiveRules();

    List<RecurringRule> findActiveRulesByCashFlowId(CashFlowId cashFlowId);

    /**
     * Finds paused rules that have a resumeDate on or before the given date.
     * Used by auto-resume scheduler to find rules that should be automatically resumed.
     *
     * @param date the date to compare against resumeDate
     * @return list of paused rules with resumeDate <= date
     */
    List<RecurringRule> findPausedRulesWithResumeDateOnOrBefore(LocalDate date);

    void delete(RecurringRuleId ruleId);

    long generateNextSequence();

    long generateNextAmountChangeSequence();

    /**
     * Count rules by user and status.
     * Used for dashboard summary.
     */
    long countByUserIdAndStatus(UserId userId, RuleStatus status);

    /**
     * Find rules by user and status.
     * Used for dashboard filtering.
     */
    List<RecurringRule> findByUserIdAndStatus(UserId userId, RuleStatus status);
}
