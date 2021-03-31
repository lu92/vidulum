package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;

public class PortfolioNotFoundException extends RuntimeException{

    public PortfolioNotFoundException(PortfolioId portfolioId) {
        super(String.format("Portfolio [%s] not found", portfolioId.getId()));
    }
}
