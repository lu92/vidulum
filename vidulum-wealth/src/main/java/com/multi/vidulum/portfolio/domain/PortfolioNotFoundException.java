package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.PortfolioId;

public class PortfolioNotFoundException extends RuntimeException{

    public PortfolioNotFoundException(PortfolioId portfolioId) {
        super(String.format("Portfolio [%s] not found", portfolioId.getId()));
    }
}
