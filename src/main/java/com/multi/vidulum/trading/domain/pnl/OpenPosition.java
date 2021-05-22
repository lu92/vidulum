package com.multi.vidulum.trading.domain.pnl;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PositionId;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@Builder
public class OpenPosition {
    PositionId positionId;
    Symbol symbol;
    PortfolioId portfolioId;
    Money balance;
    double quantity;
    Money avgPrice;
    ZonedDateTime OpeningDateTime;
}
