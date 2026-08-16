package com.multi.vidulum.cashflow.app.queries;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.queries.Query;

public record GetDetailsOfCashFlowViaUserQuery(UserId userId) implements Query {
}
