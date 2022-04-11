package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.Aggregate;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import com.multi.vidulum.trading.domain.OrderEvents.ExecutionFilledEvent;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

@Data
@Builder
public class Order implements Aggregate<OrderId, OrderSnapshot> {
    OrderId orderId;
    OriginOrderId originOrderId;
    PortfolioId portfolioId;
    Broker broker;
    Symbol symbol;
    OrderState state;
    OrderParameters parameters;
    ZonedDateTime occurredDateTime;
    private List<DomainEvent> uncommittedEvents;

    @Override
    public OrderSnapshot getSnapshot() {
        return new OrderSnapshot(
                orderId,
                originOrderId,
                portfolioId,
                broker,
                symbol,
                state,
                parameters,
                occurredDateTime
        );
    }

    public static Order from(OrderSnapshot snapshot) {
        return Order.builder()
                .orderId(snapshot.getOrderId())
                .originOrderId(snapshot.getOriginOrderId())
                .portfolioId(snapshot.getPortfolioId())
                .broker(snapshot.getBroker())
                .symbol(snapshot.getSymbol())
                .state(snapshot.getState())
                .parameters(snapshot.getParameters())
                .occurredDateTime(snapshot.getOccurredDateTime())
                .build();
    }

    public boolean isPurchaseAttempt() {
        return Side.BUY.equals(parameters.side());
    }

    public boolean isOpen() {
        return OrderStatus.OPEN.equals(state.status());
    }

    public boolean isExecuted() {
        return OrderStatus.EXECUTED.equals(state.status());
    }

    public OrderStatus fetchStatus() {
        return state.status();
    }

    public void markAsCancelled() {
        state = new OrderState(
                OrderStatus.CANCELLED,
                state.fills()
        );
    }

    private void markAsExecuted() {
        state = new OrderState(
                OrderStatus.EXECUTED,
                state.fills()
        );
    }

    public Money getTotal() {
        if (isPurchaseAttempt()) {
            Price price = OrderType.OCO.equals(parameters.type()) ? parameters.targetPrice() : parameters.limitPrice();
            return price.multiply(parameters.quantity());
        } else {
            return Money.one("USD").multiply(parameters.quantity());
        }
    }

    public void addExecution(OrderExecution execution) {
        ExecutionFilledEvent event = new ExecutionFilledEvent(
                orderId,
                execution.tradeId(),
                execution.quantity(),
                execution.price(),
                execution.dateTime());
        apply(event);
        add(event);
    }

    public void apply(ExecutionFilledEvent event) {
        if (!isOpen()) {
            throw new OrderIsNotOpenException(event.orderId());
        }

        OrderExecution orderExecution = new OrderExecution(
                event.tradeId(),
                event.quantity(),
                event.price(),
                event.dateTime());

        state.fills().add(orderExecution);

        if (isFilled()) {
            markAsExecuted();
        }
    }

    private boolean isFilled() {
        Quantity alreadyFilled = state.fills().stream()
                .map(OrderExecution::quantity)
                .reduce(Quantity.zero(), Quantity::plus);
        return alreadyFilled.equals(parameters.quantity());
    }

    public List<DomainEvent> getUncommittedEvents() {
        if (Objects.isNull(uncommittedEvents)) {
            uncommittedEvents = new LinkedList<>();
        }
        return uncommittedEvents;
    }

    private void add(DomainEvent event) {
        // store event temporary
        getUncommittedEvents().add(event);
    }

    public record OrderState(
            OrderStatus status,
            List<OrderExecution> fills) {
    }

    public record OrderExecution(
            TradeId tradeId,
            Quantity quantity,
            Price price,
            ZonedDateTime dateTime) {
    }

    public record OrderParameters(
            OrderType type,
            Side side,
            Price targetPrice,
            Price stopPrice,
            Price limitPrice, // price which appears in order-book, [null] for market price/market order
            Quantity quantity) {
    }
}
