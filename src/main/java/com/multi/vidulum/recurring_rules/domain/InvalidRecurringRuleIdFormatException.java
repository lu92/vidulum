package com.multi.vidulum.recurring_rules.domain;

import lombok.Getter;

/**
 * Exception thrown when a RecurringRule ID does not match the expected format.
 * Valid format: RRXXXXXXXX (RR followed by 8 digits, e.g., RR00000001)
 */
@Getter
public class InvalidRecurringRuleIdFormatException extends RuntimeException {

    private final String providedId;

    public InvalidRecurringRuleIdFormatException(String providedId) {
        super("Invalid RecurringRule ID format: '" + providedId + "'. Expected: RRXXXXXXXX (e.g., RR00000001)");
        this.providedId = providedId;
    }
}
