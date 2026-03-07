package com.multi.vidulum.recurring_rules.domain.exceptions;

import com.multi.vidulum.recurring_rules.domain.AmountChangeId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import lombok.Getter;

/**
 * Exception thrown when an AmountChange is not found in a RecurringRule.
 */
@Getter
public class AmountChangeNotFoundException extends RecurringRuleException {

    private final RecurringRuleId ruleId;
    private final AmountChangeId amountChangeId;

    public AmountChangeNotFoundException(RecurringRuleId ruleId, AmountChangeId amountChangeId) {
        super(String.format("Amount change [%s] not found in rule [%s]",
                amountChangeId.id(), ruleId.id()));
        this.ruleId = ruleId;
        this.amountChangeId = amountChangeId;
    }
}
