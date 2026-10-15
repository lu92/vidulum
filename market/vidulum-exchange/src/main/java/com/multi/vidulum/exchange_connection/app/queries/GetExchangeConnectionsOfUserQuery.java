package com.multi.vidulum.exchange_connection.app.queries;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.queries.Query;

public record GetExchangeConnectionsOfUserQuery(UserId userId) implements Query {
}
