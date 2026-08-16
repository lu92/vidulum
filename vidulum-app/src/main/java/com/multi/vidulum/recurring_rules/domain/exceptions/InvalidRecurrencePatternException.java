package com.multi.vidulum.recurring_rules.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Thrown when a recurrence pattern configuration is invalid.
 */
public class InvalidRecurrencePatternException extends BusinessException {

    private final String patternType;
    private final String reason;

    public InvalidRecurrencePatternException(String patternType, String reason) {
        super(String.format("Invalid %s pattern: %s", patternType, reason));
        this.patternType = patternType;
        this.reason = reason;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.RECURRING_RULE_INVALID_PATTERN;
    }

    public String getPatternType() {
        return patternType;
    }

    public String getReason() {
        return reason;
    }
}
