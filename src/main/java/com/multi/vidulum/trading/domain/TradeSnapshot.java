package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class TradeSnapshot implements EntitySnapshot<TradeId> {

    TradeId tradeId;
    UserId userId;
    PortfolioId portfolioId;
    OriginTradeId originTradeId; // generated by exchange
    Symbol symbol;
    SubName subName;
    Side side;
    Quantity quantity;
    Money price;
    ZonedDateTime dateTime;

    @Override
    public TradeId id() {
        return tradeId;
    }
}
