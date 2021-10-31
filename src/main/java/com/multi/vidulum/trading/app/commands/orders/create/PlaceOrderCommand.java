package com.multi.vidulum.trading.app.commands.orders.create;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class PlaceOrderCommand implements Command {

    OriginOrderId originOrderId;
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
}
