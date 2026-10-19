package com.multi.vidulum.cashflow.app.queries;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.shared.cqrs.queries.Query;

/**
 * How many cash changes attributed to one source rule are still predictions, and how many have
 * become facts.
 *
 * <p>Asked by two callers for the same answer - {@code CashFlowModuleApi.countCashChangesByStatus} and
 * the endpoint its HTTP transport calls - which is why it is a query with a handler rather than an
 * arithmetic helper somewhere. The handler is the one place the counting happens.
 */
public record GetCountOfCashChangesByStatusQuery(CashFlowId cashFlowId, String sourceRuleId) implements Query {
}
