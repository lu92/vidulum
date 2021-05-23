package com.multi.vidulum.portfolio.app.commands.update;

import com.multi.vidulum.common.StoredTrade;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApplyTradeCommand implements Command {
    StoredTrade trade;
}
