package com.multi.vidulum.risk_management.app.queries;

import com.multi.vidulum.risk_management.domain.RiskManagementEngine;
import com.multi.vidulum.risk_management.domain.RiskManagementStatement;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@AllArgsConstructor
public class GetRiskManagementViewQueryHandler implements QueryHandler<GetRiskManagementStatementQuery, RiskManagementStatement> {

    private final RiskManagementEngine riskManagementEngine;

    @Override
    public RiskManagementStatement query(GetRiskManagementStatementQuery query) {
        return riskManagementEngine.accept(query.getPortfolioId());
    }
}
