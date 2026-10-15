package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Confirmation was attempted while something is still open.
 *
 * <p>Silence is not an answer. §4.7 requires positions without a price to be explicitly confirmed
 * as unknown, precisely so that "nobody got round to it" cannot end up looking like a decision.
 */
public class UnansweredQuestionsException extends BusinessException {

    public UnansweredQuestionsException(PortfolioSpecId id, int open) {
        super("Specification [" + id.getId() + "] still has " + open + " unanswered question(s)");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SPEC_UNANSWERED;
    }
}
