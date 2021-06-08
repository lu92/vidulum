package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class OrderSnapshot implements EntitySnapshot<OrderId> {
    OrderId orderId;
    OrderId originOrderId;
    PortfolioId portfolioId;
    Symbol symbol;
    OrderType type;
    Side side;
    Money targetPrice;
    Money entryPrice;
    Money stopLoss;
    Quantity quantity;
    ZonedDateTime occurredDateTime;
    Status status;

    @Override
    public OrderId id() {
        return orderId;
    }
}
