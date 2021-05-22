package com.multi.vidulum.trading.domain.pnl;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PositionId;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.domain.Trade;

import java.time.ZonedDateTime;
import java.util.List;

public class ClosedPosition {
    PositionId positionId;
    Symbol symbol;
    PortfolioId portfolioId;
    Money balance;
    double quantity;
    Money avgPrice;
    Money profit;
    ZonedDateTime closedDateTime;
    List<Trade> trades;
}
