package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.trading.domain.Order.OrderExecution;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for Order aggregate — no Spring context, no Testcontainers.
 * Uses {@link InMemoryOrderRepository} with entity round-trip.
 */
@Slf4j
class OrderTest {

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneId.of("UTC"));
    private final OrderFactory orderFactory = new OrderFactory();
    private final DomainOrderRepository orderRepository = new InMemoryOrderRepository(clock);

    private static final Broker BROKER = Broker.of("Broker");

    @Test
    public void shouldCreateAndCancelOrder() {
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

        savedOrder.markAsCancelled();
        Order cancelledOrder = orderRepository.save(savedOrder);

        assertThat(cancelledOrder).isEqualTo(
                Order.builder()
                        .orderId(orderId)
                        .originOrderId(OriginOrderId.notDefined())
                        .portfolioId(portfolioId)
                        .broker(BROKER)
                        .symbol(Symbol.of("BTC/USD"))
                        .state(
                                new Order.OrderState(
                                        OrderStatus.CANCELLED,
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
                        ),
                        new OrderEvents.OrderCancelledEvent(
                                orderId
                        )
                );

        assertThat(cancelledOrder.getUncommittedEvents()).isEmpty();
    }

    @Test
    public void shouldMakeTwoFillsWithExecutedStatus() {
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

        Order persistedOrder = orderRepository.save(order);

        persistedOrder.addExecution(
                new OrderExecution(
                        TradeId.of("trade-1"),
                        Quantity.of(0.1),
                        Price.of(30000.0, "USD"),
                        ZonedDateTime.parse("2021-06-01T08:30:00Z")
                ));

        Order persistedOrder2 = orderRepository.save(persistedOrder);

        assertThat(persistedOrder2)
                .isEqualTo(
                        Order.builder()
                                .orderId(orderId)
                                .originOrderId(OriginOrderId.notDefined())
                                .portfolioId(portfolioId)
                                .broker(BROKER)
                                .symbol(Symbol.of("BTC/USD"))
                                .state(
                                        new Order.OrderState(
                                                OrderStatus.OPEN,
                                                List.of(
                                                        new OrderExecution(
                                                                TradeId.of("trade-1"),
                                                                Quantity.of(0.1),
                                                                Price.of(30000.0, "USD"),
                                                                ZonedDateTime.parse("2021-06-01T08:30:00Z")
                                                        )
                                                )))
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

        persistedOrder2.addExecution(
                new OrderExecution(
                        TradeId.of("trade-2"),
                        Quantity.of(0.1),
                        Price.of(30000.0, "USD"),
                        ZonedDateTime.parse("2021-06-01T09:30:00Z")
                ));

        Order persistedOrder3 = orderRepository.save(persistedOrder2);

        assertThat(persistedOrder3)
                .isEqualTo(
                        Order.builder()
                                .orderId(orderId)
                                .originOrderId(OriginOrderId.notDefined())
                                .portfolioId(portfolioId)
                                .broker(BROKER)
                                .symbol(Symbol.of("BTC/USD"))
                                .state(
                                        new Order.OrderState(
                                                OrderStatus.EXECUTED,
                                                List.of(
                                                        new OrderExecution(
                                                                TradeId.of("trade-1"),
                                                                Quantity.of(0.1),
                                                                Price.of(30000.0, "USD"),
                                                                ZonedDateTime.parse("2021-06-01T08:30:00Z")
                                                        ),
                                                        new OrderExecution(
                                                                TradeId.of("trade-2"),
                                                                Quantity.of(0.1),
                                                                Price.of(30000.0, "USD"),
                                                                ZonedDateTime.parse("2021-06-01T09:30:00Z")
                                                        )

                                                )))
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

        assertThat(orderRepository.findDomainEvents(orderId))
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
                        ),
                        new OrderEvents.ExecutionFilledEvent(
                                orderId,
                                TradeId.of("trade-1"),
                                Quantity.of(0.1),
                                Price.of(30000.0, "USD"),
                                ZonedDateTime.parse("2021-06-01T08:30:00Z")
                        ),
                        new OrderEvents.ExecutionFilledEvent(
                                orderId,
                                TradeId.of("trade-2"),
                                Quantity.of(0.1),
                                Price.of(30000.0, "USD"),
                                ZonedDateTime.parse("2021-06-01T09:30:00Z")
                        )
                );

        assertThat(persistedOrder3.getUncommittedEvents()).isEmpty();
    }

    /**
     * A sale has no cost to state, and saying so is the guard that replaced a working accident.
     *
     * <p>{@code getTotal()} used to answer {@code Money.one("USD") x quantity} for a sale — the
     * quantity handed back dressed as dollars. Every caller multiplied it by one and read the
     * number, so it held; none of them read the currency, so nothing complained. What is being
     * protected here is not a calculation but a refusal: the next caller to ask a sale what it
     * costs is stopped instead of quietly given ounces labelled USD.
     */
    @Test
    public void shouldRefuseToStateWhatASaleCosts() {
        Order sale = orderFactory.empty(
                OrderId.generate(),
                OriginOrderId.notDefined(),
                PortfolioId.generate(),
                BROKER,
                Symbol.of("XAU/USD"),
                OrderType.LIMIT,
                Side.SELL,
                null,
                null,
                Price.of(2000, "USD"),
                Quantity.of(2, "oz"),
                ZonedDateTime.now(clock));

        assertThatThrownBy(sale::getTotal)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserves the asset");
    }

    /** A purchase does have one, and it is the money it will take. */
    @Test
    public void shouldStateWhatAPurchaseCosts() {
        Order purchase = orderFactory.empty(
                OrderId.generate(),
                OriginOrderId.notDefined(),
                PortfolioId.generate(),
                BROKER,
                Symbol.of("XAU/USD"),
                OrderType.LIMIT,
                Side.BUY,
                null,
                null,
                Price.of(1800, "USD"),
                Quantity.of(2, "oz"),
                ZonedDateTime.now(clock));

        assertThat(purchase.getTotal()).isEqualTo(Money.of(3600, "USD"));
    }
}
