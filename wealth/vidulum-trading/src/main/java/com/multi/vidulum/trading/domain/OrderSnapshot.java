package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.OriginOrderId;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.PortfolioId;
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

    /**
     * The document version this order was read at, so the save that follows is conditional
     * (task F57). Null means it has not been stored yet.
     */
    Long version;

    @Override
    public OrderId id() {
        return orderId;
    }
}
