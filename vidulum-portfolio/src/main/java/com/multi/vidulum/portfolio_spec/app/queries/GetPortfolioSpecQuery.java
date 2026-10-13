package com.multi.vidulum.portfolio_spec.app.queries;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.shared.cqrs.queries.Query;

public record GetPortfolioSpecQuery(UserId userId, PortfolioSpecId specId) implements Query {
}
