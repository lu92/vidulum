package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class PnlTradeDetails {
    TradeId originTradeId;
    PortfolioId portfolioId;
    Symbol symbol;
    SubName subName;
    Side side;
    Quantity quantity;
    Money price;
    ZonedDateTime originDateTime;
}
