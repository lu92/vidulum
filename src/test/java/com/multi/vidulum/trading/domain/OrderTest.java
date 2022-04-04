package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class OrderTest extends IntegrationTest {

    private static final Broker BROKER = Broker.of("Broker");

    @Test
    public void shouldCreateOrder() {
        OrderId orderId = OrderId.generate();
        PortfolioId portfolioId = PortfolioId.generate();
        Order order = orderFactory.empty(
                orderId,
                OriginOrderId.notDefined(),
                portfolioId,
                BROKER,
                Symbol.of("BTC/USD"),
                OrderType.LIMIT,
                Side.BUY,
                null,
                null,
                Price.of(30000.0, "USD"),
                Quantity.of(0.2),
                ZonedDateTime.parse("2021-06-01T06:30:00Z"));

        Order savedOrder = orderRepository.save(order);

        assertThat(savedOrder).isEqualTo(
                Order.builder()
                        .orderId(orderId)
                        .originOrderId(OriginOrderId.notDefined())
                        .portfolioId(portfolioId)
                        .broker(BROKER)
                        .symbol(Symbol.of("BTC/USD"))
                        .state(
                                new Order.OrderState(
                                        OrderStatus.OPEN,
                                        List.of()))
                        .parameters(
                                new Order.OrderParameters(
                                        OrderType.LIMIT,
                                        Side.BUY,
                                        null,
                                        null,
                                        Price.of(30000.0, "USD"),
                                        Quantity.of(0.2)))
                        .occurredDateTime(ZonedDateTime.parse("2021-06-01T06:30:00Z"))
                        .build()
        );

        assertThat(orderRepository.findDomainEvents(savedOrder.getOrderId()))
                .containsExactlyInAnyOrder(
                        new OrderEvents.OrderCreatedEvent(
                                orderId,
                                OriginOrderId.notDefined(),
                                portfolioId,
                                BROKER,
                                Symbol.of("BTC/USD"),
                                OrderType.LIMIT,
                                Side.BUY,
                                null,
                                null,
                                Price.of(30000.0, "USD"),
                                Quantity.of(0.2),
                                ZonedDateTime.parse("2021-06-01T06:30:00Z")
                        )
                );

        assertThat(savedOrder.getUncommittedEvents()).isEmpty();
    }
}
