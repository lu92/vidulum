package com.multi.vidulum.common;

// PortfolioId is now in common package - no import needed
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredTrade {
    TradeId tradeId;
    UserId userId;
    OriginTradeId originTradeId;
    PortfolioId portfolioId;
    OrderId originOrderId;
    Symbol symbol;
    SubName subName;
    Side side;
    Quantity quantity;
    Price price;
    ZonedDateTime dateTime;
}
