package com.multi.vidulum.trading.app.commands.orders.execute;

import com.multi.vidulum.common.*;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OrderExecutionSummary {
    OriginOrderId originOrderId;
    OriginTradeId originTradeId;
    Symbol symbol;
    OrderType type;
    Side side;
    Quantity quantity;
    Money profit;
}
