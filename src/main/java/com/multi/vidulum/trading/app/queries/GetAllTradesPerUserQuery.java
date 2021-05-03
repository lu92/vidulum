package com.multi.vidulum.trading.app.queries;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.queries.Query;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class GetAllTradesPerUserQuery implements Query {
    private final UserId userId;
}
