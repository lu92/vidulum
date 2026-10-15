package com.multi.vidulum.pnl.app.commands;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SetupPnlHistoryCommand implements Command {
    UserId userId;
}
