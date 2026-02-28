package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.UserId;

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

    void delete(RecurringRuleId ruleId);

    long generateNextSequence();
}
