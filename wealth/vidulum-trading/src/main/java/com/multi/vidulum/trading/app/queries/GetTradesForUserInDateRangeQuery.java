package com.multi.vidulum.trading.app.queries;

import com.multi.vidulum.common.Range;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.queries.Query;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class GetTradesForUserInDateRangeQuery implements Query {
    UserId userId;
    Range<ZonedDateTime> dateTimeRange;
}
