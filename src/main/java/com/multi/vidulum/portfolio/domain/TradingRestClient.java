package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.app.TradingDto;

import java.util.List;

public interface TradingRestClient {

    List<TradingDto.OrderSummaryJson> getOpenedOrders(PortfolioId portfolioId);
}
