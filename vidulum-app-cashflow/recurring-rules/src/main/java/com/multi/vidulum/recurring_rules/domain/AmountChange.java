package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.common.Money;

import java.util.Objects;

/**
 * Represents a change in amount for a recurring rule.
 * Can be ONE_TIME (applies once) or PERMANENT (changes future executions).
 */
public record AmountChange(
        AmountChangeId id,
        Money amount,
        AmountChangeType type,
        String reason
) {
    public AmountChange {
        Objects.requireNonNull(id, "Amount change ID cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        Objects.requireNonNull(type, "Amount change type cannot be null");
    }

    public static AmountChange oneTime(AmountChangeId id, Money amount, String reason) {
        return new AmountChange(id, amount, AmountChangeType.ONE_TIME, reason);
    }

    public static AmountChange permanent(AmountChangeId id, Money amount, String reason) {
        return new AmountChange(id, amount, AmountChangeType.PERMANENT, reason);
    }
}
