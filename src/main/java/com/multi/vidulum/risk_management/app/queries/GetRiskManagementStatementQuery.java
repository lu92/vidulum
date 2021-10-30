package com.multi.vidulum.risk_management.app.queries;

import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.queries.Query;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GetRiskManagementStatementQuery implements Query {
    private final PortfolioId portfolioId;
}
