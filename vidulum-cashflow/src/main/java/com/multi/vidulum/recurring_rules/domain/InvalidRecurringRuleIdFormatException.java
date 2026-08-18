package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

/**
 * Exception thrown when a RecurringRule ID does not match the expected format.
 * Valid format: RRXXXXXXXX (RR followed by 8 digits, e.g., RR00000001)
 */
@Getter
public class InvalidRecurringRuleIdFormatException extends BusinessException {

    private final String providedId;

    public InvalidRecurringRuleIdFormatException(String providedId) {
        super("Invalid RecurringRule ID format: '" + providedId + "'. Expected: RRXXXXXXXX (e.g., RR00000001)");
        this.providedId = providedId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INVALID_RECURRING_RULE_ID_FORMAT;
    }
}
