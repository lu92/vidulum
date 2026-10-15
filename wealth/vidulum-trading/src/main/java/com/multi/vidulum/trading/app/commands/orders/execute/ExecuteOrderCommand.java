package com.multi.vidulum.trading.app.commands.orders.execute;

import com.multi.vidulum.common.*;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class ExecuteOrderCommand implements Command {
    OriginOrderId originOrderId;
    OriginTradeId originTradeId;
    Quantity quantity;
    Price price;
    Money exchangeCurrencyFee;
    Money transactionFee;
    ZonedDateTime originDateTime;
}
