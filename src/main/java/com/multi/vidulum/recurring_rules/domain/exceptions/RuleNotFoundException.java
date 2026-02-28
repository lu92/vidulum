package com.multi.vidulum.recurring_rules.domain.exceptions;

import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;

/**
 * Thrown when a recurring rule is not found.
 */
public class RuleNotFoundException extends RecurringRuleException {

    private final RecurringRuleId ruleId;

    public RuleNotFoundException(RecurringRuleId ruleId) {
        super("Recurring rule not found: " + ruleId.id());
        this.ruleId = ruleId;
    }

    public RecurringRuleId getRuleId() {
        return ruleId;
    }
}
