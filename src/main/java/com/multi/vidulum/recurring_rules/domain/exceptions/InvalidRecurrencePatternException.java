package com.multi.vidulum.recurring_rules.domain.exceptions;

/**
 * Thrown when a recurrence pattern configuration is invalid.
 * This is an unchecked exception to allow throwing from record constructors.
 */
public class InvalidRecurrencePatternException extends RuntimeException {

    private final String patternType;
    private final String reason;

    public InvalidRecurrencePatternException(String patternType, String reason) {
        super(String.format("Invalid %s pattern: %s", patternType, reason));
        this.patternType = patternType;
        this.reason = reason;
    }

    public String getPatternType() {
        return patternType;
    }

    public String getReason() {
        return reason;
    }
}
