package com.multi.vidulum.trading.app.commands;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CancelOrderCommand implements Command {
    OrderId originOrderId;
}
