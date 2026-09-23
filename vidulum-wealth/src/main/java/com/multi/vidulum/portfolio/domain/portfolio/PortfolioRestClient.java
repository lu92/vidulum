package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.common.PortfolioId;

public interface PortfolioRestClient {
    PortfolioId createPortfolio(String name, UserId userId, Broker broker, Currency allowedDepositCurrency);

    void lockAsset(PortfolioId portfolioId, Ticker ticker, OrderId orderId, Quantity quantity);

    void unlockAsset(PortfolioId portfolioId, Ticker ticker, OrderId orderId, Quantity quantity);

    /**
     * The portfolio valued in <b>its own</b> currency.
     *
     * <p>Every implementation used to answer in USD regardless, and the balance check that
     * precedes an order is built on this: a portfolio settling in PLN had its holdings compared
     * against dollar amounts, and needed USD quotes for assets it never priced that way.
     */
    PortfolioDto.PortfolioSummaryJson getPortfolio(PortfolioId portfolioId);

    PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(UserId userId);
}
