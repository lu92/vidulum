package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import lombok.AllArgsConstructor;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;

@AllArgsConstructor
public class OrderFactory {

    public Order empty(
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
            ZonedDateTime occurredDateTime
    ) {

        List<DomainEvent> uncommittedEvents = new LinkedList<>();
        uncommittedEvents.add(
                new OrderEvents.OrderCreatedEvent(
                        orderId,
                        originOrderId,
                        portfolioId,
                        broker,
                        symbol,
                        type,
                        side,
                        targetPrice,
                        stopPrice,
                        limitPrice,
                        quantity,
                        occurredDateTime
                )
        );

        return Order.builder()
                .orderId(orderId)
                .originOrderId(originOrderId)
                .portfolioId(portfolioId)
                .broker(broker)
                .symbol(symbol)
                .state(
                        new Order.OrderState(
                                OrderStatus.OPEN,
                                new LinkedList<>()))
                .parameters(
                        new Order.OrderParameters(
                                type,
                                side,
                                targetPrice,
                                stopPrice,
                                limitPrice,
                                quantity))
                .occurredDateTime(occurredDateTime)
                .uncommittedEvents(uncommittedEvents)
                .build();
    }
}
