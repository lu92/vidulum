package com.multi.vidulum.trading.app.commands;

import com.multi.vidulum.common.OriginOrderId;
import com.multi.vidulum.common.OriginTradeId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class ExecuteOrderCommand implements Command {
    OriginOrderId originOrderId;
    OriginTradeId originTradeId;
    ZonedDateTime originDateTime;
}
