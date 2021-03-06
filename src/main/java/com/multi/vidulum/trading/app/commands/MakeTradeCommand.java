package com.multi.vidulum.trading.app.commands;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class MakeTradeCommand implements Command {
    UserId userId;
    PortfolioId portfolioId;
    OriginTradeId originTradeId; // generated by exchange
    Symbol symbol;
    SubName subName;
    Side side;
    Quantity quantity;
    Money price;
    ZonedDateTime originDateTime;
}
