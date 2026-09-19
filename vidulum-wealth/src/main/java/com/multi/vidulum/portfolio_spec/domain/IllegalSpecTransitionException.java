package com.multi.vidulum.portfolio_spec.domain;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class IllegalSpecTransitionException extends BusinessException {

    public IllegalSpecTransitionException(PortfolioSpecId id, SpecStatus current, String operation) {
        super("Cannot " + operation + " specification [" + id.getId() + "] while it is " + current);
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SPEC_INVALID_TRANSITION;
    }
}
