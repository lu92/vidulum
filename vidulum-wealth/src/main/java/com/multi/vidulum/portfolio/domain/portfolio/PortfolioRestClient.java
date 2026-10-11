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

    /**
     * The portfolio valued in the currency asked for (task C17).
     *
     * <p>Separate from {@link #getPortfolio(PortfolioId)}, which answers in the portfolio's own
     * currency and is what an order's balance check wants. Recording history wants this one: the
     * currency has to be the same across every portfolio in a statement, and the portfolios need
     * not agree on one.
     */
    PortfolioDto.PortfolioSummaryJson getPortfolio(PortfolioId portfolioId, Currency denominatedIn);

    /**
     * Everything the owner holds, valued in the currency asked for.
     *
     * <p>The currency used to be {@code USD}, written into the implementation — invisible to every
     * caller and impossible to disagree with. That was not a decision while the backend could not
     * value a portfolio in anything else; after B4 it is, so it is made by whoever asks.
     */
    PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(UserId userId, Currency denominatedIn);
}
