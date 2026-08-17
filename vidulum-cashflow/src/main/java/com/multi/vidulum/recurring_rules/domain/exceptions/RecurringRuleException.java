package com.multi.vidulum.recurring_rules.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Base exception for all recurring rule domain errors.
 */
public abstract class RecurringRuleException extends BusinessException {

    protected RecurringRuleException(String message) {
        super(message);
    }

    protected RecurringRuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
