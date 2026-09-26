package com.multi.vidulum.portfolio.domain.trades;

import com.multi.vidulum.common.*;
import lombok.Builder;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Builder
public class ExecutedTrade {
    PortfolioId portfolioId;
    TradeId tradeId;
    OrderId orderId;
    Symbol symbol;
    SubName subName;
    Side side;
    Quantity quantity;
    Price price;

    /** When it happened — what a realised result is dated by (task F6). */
    ZonedDateTime dateTime;
}
