package com.multi.vidulum.exchange_connection.app.queries;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.shared.cqrs.queries.Query;

public record GetExchangeConnectionQuery(
        UserId userId,
        ExchangeConnectionId connectionId) implements Query {
}
