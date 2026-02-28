package com.multi.vidulum.recurring_rules.domain.exceptions;

/**
 * Base checked exception for all recurring rule domain errors.
 * This is intentionally a checked exception to force explicit error handling.
 */
public class RecurringRuleException extends Exception {

    public RecurringRuleException(String message) {
        super(message);
    }

    public RecurringRuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
