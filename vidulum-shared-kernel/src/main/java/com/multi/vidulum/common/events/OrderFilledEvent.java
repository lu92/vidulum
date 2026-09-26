package com.multi.vidulum.common.events;

import com.multi.vidulum.common.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderFilledEvent {
    OrderId orderId;
    PortfolioId portfolioId;
    TradeId tradeId;
    Symbol symbol;
    SubName subName;
    Side side;
    Quantity quantity;
    Price price;

    /** When the fill happened — carried on so a realised result can be dated by it (task F6). */
    ZonedDateTime dateTime;
}
