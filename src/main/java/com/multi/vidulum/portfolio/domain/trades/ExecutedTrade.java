package com.multi.vidulum.portfolio.domain.trades;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Getter;

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
}
