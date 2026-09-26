package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.PortfolioStatus;
import com.multi.vidulum.common.PortfolioId;

public class PortfolioIsNotOpenedException extends BusinessException{

    public PortfolioIsNotOpenedException(PortfolioId portfolioId, PortfolioStatus currentStatus) {
        super(String.format("Portfolio [%s] is not opened (current status: %s)", portfolioId.getId(), currentStatus));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_NOT_OPENED;
    }
}
