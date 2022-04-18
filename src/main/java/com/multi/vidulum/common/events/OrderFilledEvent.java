package com.multi.vidulum.common.events;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderFilledEvent {
    OrderId orderId;
    PortfolioId portfolioId;
    TradeId tradeId;
    Symbol symbol;
    SubName subName;
    Side side;
    Quantity quantity;
    Price price;
}
