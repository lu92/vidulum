package com.multi.vidulum.trading.app.commands;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class PlaceOrderCommand implements Command {

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
}
