package com.multi.vidulum.risk_management.app;


import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.risk_management.app.queries.GetRiskManagementStatementQuery;
import com.multi.vidulum.risk_management.domain.RiskManagementStatement;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class RiskManagementRestController {
    private final QueryGateway queryGateway;
    private final RiskManagementMapper mapper;

    @GetMapping("/risk-management/{portfolioId}")
    public RiskManagementDto.RiskManagementStatementJson getRiskManagementStatement(@PathVariable("portfolioId") String portfolioId) {
        GetRiskManagementStatementQuery query = GetRiskManagementStatementQuery.builder()
                .portfolioId(PortfolioId.of(portfolioId))
                .build();

        RiskManagementStatement statement = queryGateway.send(query);
        return mapper.toJson(statement);
    }
}
