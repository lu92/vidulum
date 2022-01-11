package com.multi.vidulum.common.events;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
    OrderId orderId;
    OriginOrderId originOrderId;
    PortfolioId portfolioId;
    Broker broker;
    Symbol symbol;
    OrderType type;
    Side side;
    Money targetPrice;
    Money stopPrice;
    Money limitPrice;
    Quantity quantity;
    ZonedDateTime occurredDateTime;
    double riskRewardRatio; // only for OCO orders
    Status status;
}
