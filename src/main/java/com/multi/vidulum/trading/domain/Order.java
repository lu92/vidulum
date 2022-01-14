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
    OriginOrderId originOrderId;
    PortfolioId portfolioId;
    Broker broker;
    Symbol symbol;
    OrderType type;
    Side side;
    Price targetPrice;
    Price stopPrice;
    Price limitPrice; // price which appears in onder-book, [null] for market price/market order
    Quantity quantity;
    ZonedDateTime occurredDateTime;
    double riskRewardRatio;
    Status status;

    @Override
    public OrderSnapshot getSnapshot() {
        return new OrderSnapshot(
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
                occurredDateTime,
                status
        );
    }

    public static Order from(OrderSnapshot snapshot) {
        return Order.builder()
                .orderId(snapshot.getOrderId())
                .originOrderId(snapshot.getOriginOrderId())
                .portfolioId(snapshot.getPortfolioId())
                .broker(snapshot.getBroker())
                .symbol(snapshot.getSymbol())
                .type(snapshot.getType())
                .side(snapshot.getSide())
                .targetPrice(snapshot.getTargetPrice())
                .stopPrice(snapshot.getStopPrice())
                .limitPrice(snapshot.getLimitPrice())
                .quantity(snapshot.getQuantity())
                .occurredDateTime(snapshot.getOccurredDateTime())
                .status(snapshot.getStatus())
                .build();
    }

    public boolean isPurchaseAttempt() {
        return Side.BUY.equals(side);
    }

    public boolean isOpen() {
        return Status.OPEN.equals(status);
    }

    public Money getTotal() {
        if (isPurchaseAttempt()) {
            Price price = OrderType.OCO.equals(type) ? targetPrice : limitPrice;
            return price.multiply(quantity);
        } else {
            return Money.one("USD").multiply(quantity);
        }
    }
}
