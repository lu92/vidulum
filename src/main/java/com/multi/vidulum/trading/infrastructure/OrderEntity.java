package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.OrderSnapshot;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Builder
@Getter
@ToString
@Document("orders")
public class OrderEntity {

    @Id
    private String id;
    private String orderId;
    private String originOrderId;
    private String portfolioId;
    private String broker;
    private String symbol;
    private OrderStateEntity state;
    private OrderParametersEntity parameters;
    private Date occurredDateTime;


    public static OrderEntity fromSnapshot(OrderSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.getOrderId())
                .map(OrderId::getId).orElse(null);
        Date date = snapshot.getOccurredDateTime() != null ? Date.from(snapshot.getOccurredDateTime().toInstant()) : null;

        List<OrderExecutionEntity> executionEntities = snapshot.getState().fills().stream()
                .map(execution -> new OrderExecutionEntity(
                        execution.tradeId().getId(),
                        execution.quantity(),
                        execution.price(),
                        execution.dateTime()
                ))
                .toList();

        OrderStateEntity stateEntity = new OrderStateEntity(
                snapshot.getState().status(),
                executionEntities
        );

        OrderParametersEntity parametersEntity = new OrderParametersEntity(
                snapshot.getParameters().type(),
                snapshot.getParameters().side(),
                snapshot.getParameters().targetPrice(),
                snapshot.getParameters().stopPrice(),
                snapshot.getParameters().limitPrice(),
                snapshot.getParameters().quantity()
        );

        return OrderEntity.builder()
                .id(id)
                .orderId(snapshot.getOrderId().getId())
                .originOrderId(snapshot.getOriginOrderId().getId())
                .portfolioId(snapshot.getPortfolioId().getId())
                .broker(snapshot.getBroker().getId())
                .symbol(snapshot.getSymbol().getId())
                .state(stateEntity)
                .parameters(parametersEntity)
                .occurredDateTime(date)
                .build();
    }

    public OrderSnapshot toSnapshot() {
        ZonedDateTime zonedDateTime = occurredDateTime != null ? ZonedDateTime.ofInstant(occurredDateTime.toInstant(), ZoneOffset.UTC) : null;


        Order.OrderParameters orderParameters = new Order.OrderParameters(
                parameters.type(),
                parameters.side(),
                parameters.targetPrice(),
                parameters.stopPrice(),
                parameters.limitPrice(),
                parameters.quantity()
        );

        List<Order.OrderExecution> orderExecutions = state.fills().stream()
                .map(orderExecutionEntity ->
                        new Order.OrderExecution(
                                TradeId.of(orderExecutionEntity.tradeId()),
                                orderExecutionEntity.quantity(),
                                orderExecutionEntity.price(),
                                orderExecutionEntity.dateTime()
                        )).toList();

        Order.OrderState orderState = new Order.OrderState(
                state.status(),
                orderExecutions
        );
        return OrderSnapshot.builder()
                .orderId(OrderId.of(orderId))
                .originOrderId(OriginOrderId.of(originOrderId))
                .portfolioId(PortfolioId.of(portfolioId))
                .broker(Broker.of(broker))
                .symbol(Symbol.of(symbol))
                .state(orderState)
                .parameters(orderParameters)
                .occurredDateTime(zonedDateTime)
                .build();
    }

    record OrderStateEntity(
            OrderStatus status,
            List<OrderExecutionEntity> fills) {
    }

    record OrderExecutionEntity(
            String tradeId,
            Quantity quantity,
            Price price,
            ZonedDateTime dateTime) {
    }

    public record OrderParametersEntity(
            OrderType type,
            Side side,
            Price targetPrice,
            Price stopPrice,
            Price limitPrice,
            Quantity quantity) {
    }
}
