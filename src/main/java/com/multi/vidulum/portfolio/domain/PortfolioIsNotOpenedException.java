package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.PortfolioStatus;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;

public class PortfolioIsNotOpenedException extends RuntimeException{

    public PortfolioIsNotOpenedException(PortfolioId portfolioId, PortfolioStatus currentStatus) {
        super(String.format("Portfolio [%s] is not opened (current status: %s)", portfolioId.getId(), currentStatus));
    }
}
