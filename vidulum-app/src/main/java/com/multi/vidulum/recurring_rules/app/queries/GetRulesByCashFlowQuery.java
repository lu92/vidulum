package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.shared.cqrs.queries.Query;

public record GetRulesByCashFlowQuery(String cashFlowId) implements Query {
}
