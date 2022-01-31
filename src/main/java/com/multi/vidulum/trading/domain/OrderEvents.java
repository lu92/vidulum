package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.time.ZonedDateTime;

public final class OrderEvents {

    public record OrderCreatedEvent(
            OrderId orderId,
            OriginOrderId originOrderId,
            PortfolioId portfolioId,
            Broker broker,
            Symbol symbol,
            OrderType type,
            Side side,
            Price targetPrice,
            Price stopPrice,
            Price limitPrice,
            Quantity quantity,
            ZonedDateTime occurredDateTime) implements DomainEvent {
    }

    public record OrderCancelledEvent(
            OrderId orderId
    ) implements DomainEvent {
    }

    public record OrderExecutedEvent(
            OrderId orderId
    ) implements DomainEvent {
    }
}
