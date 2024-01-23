package com.multi.vidulum.cashflow.app.queries;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.shared.cqrs.queries.Query;

public record GetCashChangeQuery(CashChangeId cashChangeId) implements Query {
}
