package com.multi.vidulum.user.domain;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;

public interface PortfolioRestClient {
    PortfolioId createPortfolio(String name, UserId userId, Broker broker);

    void unlockAsset(PortfolioId portfolioId, Ticker ticker, Quantity quantityToUnlocked);

    PortfolioDto.PortfolioSummaryJson getPortfolio(PortfolioId portfolioId);

    PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(UserId userId);
}
