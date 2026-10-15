package com.multi.vidulum.portfolio_spec.domain;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class PortfolioSpecNotFoundException extends BusinessException {

    public PortfolioSpecNotFoundException(PortfolioSpecId id) {
        super("Portfolio specification [" + id.getId() + "] not found");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SPEC_NOT_FOUND;
    }
}
