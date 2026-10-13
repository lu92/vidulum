package com.multi.vidulum.trading.app.commands.orders.fill;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;

public record FillOrderCommand(
        OrderId orderId,
        TradeId tradeId,
        Quantity quantity,
        Price price,
        ZonedDateTime dateTime) implements Command {
}
