package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@Builder
public class Order implements Aggregate<OrderId, OrderSnapshot> {
    OrderId orderId;
    OrderId originOrderId;
    PortfolioId portfolioId;
    Broker broker;
    Symbol symbol;
    OrderType type;
    Side side;
    Money targetPrice;
    Money entryPrice;
    Money stopLoss;
    Quantity quantity;
    ZonedDateTime occurredDateTime;
    double RiskRewardRatio;
    Status status;

    @Override
    public OrderSnapshot getSnapshot() {
        return new OrderSnapshot(
                orderId,
                originOrderId,
                portfolioId,
                symbol,
                type,
                side,
                targetPrice,
                entryPrice,
                stopLoss,
                quantity,
                occurredDateTime,
                status
        );
    }

    public static Order from(OrderSnapshot snapshot) {
        return Order.builder()
                .orderId(snapshot.getOrderId())
                .portfolioId(snapshot.getPortfolioId())
                .originOrderId(snapshot.getOriginOrderId())
                .symbol(snapshot.getSymbol())
                .type(snapshot.getType())
                .side(snapshot.getSide())
                .targetPrice(snapshot.getTargetPrice())
                .entryPrice(snapshot.getEntryPrice())
                .stopLoss(snapshot.getStopLoss())
                .quantity(snapshot.getQuantity())
                .occurredDateTime(snapshot.getOccurredDateTime())
                .status(snapshot.getStatus())
                .build();
    }
}
