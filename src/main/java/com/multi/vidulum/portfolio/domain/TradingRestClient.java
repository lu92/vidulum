package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.Range;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.app.TradingDto;

import java.time.ZonedDateTime;
import java.util.List;

public interface TradingRestClient {

    List<TradingDto.OrderSummaryJson> getOpenedOrders(PortfolioId portfolioId);

    List<TradingDto.TradeSummaryJson> getTradesInDateRange(UserId userId, Range<ZonedDateTime> dateTimeRange);
}
