package com.multi.vidulum.pnl.app.commands;

import com.multi.vidulum.common.Range;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class MakePnlSnapshotCommand implements Command {
    UserId userId;
    Range<ZonedDateTime> dateTimeRange;
}
