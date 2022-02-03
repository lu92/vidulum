package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.OriginOrderId;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class OrderSnapshot implements EntitySnapshot<OrderId> {
    OrderId orderId;
    OriginOrderId originOrderId;
    PortfolioId portfolioId;
    Broker broker;
    Symbol symbol;
    Order.OrderState state;
    Order.OrderParameters parameters;
    ZonedDateTime occurredDateTime;

    @Override
    public OrderId id() {
        return orderId;
    }
}
