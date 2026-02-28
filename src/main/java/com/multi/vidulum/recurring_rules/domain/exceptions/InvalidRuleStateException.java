package com.multi.vidulum.recurring_rules.domain.exceptions;

import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RuleStatus;

/**
 * Thrown when an operation is attempted on a rule in an invalid state.
 */
public class InvalidRuleStateException extends RecurringRuleException {

    private final RecurringRuleId ruleId;
    private final RuleStatus currentStatus;
    private final String operation;

    public InvalidRuleStateException(RecurringRuleId ruleId, RuleStatus currentStatus, String operation) {
        super(String.format("Cannot %s rule [%s] in status %s", operation, ruleId.id(), currentStatus));
        this.ruleId = ruleId;
        this.currentStatus = currentStatus;
        this.operation = operation;
    }

    public RecurringRuleId getRuleId() {
        return ruleId;
    }

    public RuleStatus getCurrentStatus() {
        return currentStatus;
    }

    public String getOperation() {
        return operation;
    }
}
