package com.multi.vidulum.pnl.app.queries;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.queries.Query;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GetPnlHistoryQuery implements Query {
    UserId userId;
}
