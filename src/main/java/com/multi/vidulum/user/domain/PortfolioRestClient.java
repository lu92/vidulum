package com.multi.vidulum.user.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;

public interface PortfolioRestClient {
    PortfolioId createPortfolio(String name, UserId userId, Broker broker, Currency allowedDepositCurrency);

    void lockAsset(PortfolioId portfolioId, Ticker ticker, Quantity quantity);

    void unlockAsset(PortfolioId portfolioId, Ticker ticker, Quantity quantity);

    PortfolioDto.PortfolioSummaryJson getPortfolio(PortfolioId portfolioId);

    PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(UserId userId);
}
