package com.multi.vidulum.portfolio.app.commands.update;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProcessTradeCommand implements Command {
    PortfolioId portfolioId;
    TradeId tradeId;
    Symbol symbol;
    SubName subName;
    Side side;
    Quantity quantity;
    Price price;
}
