package com.multi.vidulum.cashflow.app.queries;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.queries.Query;

public record GetCashFlowQuery(CashFlowId cashFlowId) implements Query {
}
