package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@Builder
public class Trade implements Aggregate<TradeId, TradeSnapshot> {
    TradeId tradeId;
    UserId userId;
    PortfolioId portfolioId;
    OriginTradeId originTradeId; // generated by exchange
    Symbol symbol;
    Side side;
    double quantity;
    Money price;
    ZonedDateTime dateTime;

    @Override
    public TradeSnapshot getSnapshot() {
        return new TradeSnapshot(
            tradeId,
            userId,
            portfolioId,
            originTradeId,
            symbol,
            side,
            quantity,
            price,
            dateTime
        );
    }
}
