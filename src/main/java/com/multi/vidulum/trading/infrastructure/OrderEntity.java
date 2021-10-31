package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.domain.OrderSnapshot;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Optional;

@Builder
@Getter
@ToString
@Document("orders")
public class OrderEntity {

    @Id
    private String orderId;
    private String originOrderId;
    private String portfolioId;
    private String symbol;
    private OrderType type;
    private Side side;
    private Money targetPrice;
    private Money entryPrice;
    private Money stopLoss;
    private Quantity quantity;
    private Date occurredDateTime;
    private Status status;


    public static OrderEntity fromSnapshot(OrderSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.getOrderId())
                .map(OrderId::getId).orElse(null);
        Date date = snapshot.getOccurredDateTime() != null ? Date.from(snapshot.getOccurredDateTime().toInstant()) : null;
        return OrderEntity.builder()
                .orderId(id)
                .originOrderId(snapshot.getOriginOrderId().getId())
                .portfolioId(snapshot.getPortfolioId().getId())
                .symbol(snapshot.getSymbol().getId())
                .type(snapshot.getType())
                .side(snapshot.getSide())
                .targetPrice(snapshot.getTargetPrice())
                .entryPrice(snapshot.getEntryPrice())
                .stopLoss(snapshot.getStopLoss())
                .quantity(snapshot.getQuantity())
                .occurredDateTime(date)
                .status(snapshot.getStatus())
                .build();
    }

    public OrderSnapshot toSnapshot() {
        ZonedDateTime zonedDateTime = occurredDateTime != null ? ZonedDateTime.ofInstant(occurredDateTime.toInstant(), ZoneOffset.UTC) : null;
        return OrderSnapshot.builder()
                .orderId(OrderId.of(orderId))
                .originOrderId(OriginOrderId.of(originOrderId))
                .portfolioId(PortfolioId.of(portfolioId))
                .symbol(Symbol.of(symbol))
                .type(type)
                .side(side)
                .targetPrice(targetPrice)
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .quantity(quantity)
                .occurredDateTime(zonedDateTime)
                .status(status)
                .build();
    }
}
