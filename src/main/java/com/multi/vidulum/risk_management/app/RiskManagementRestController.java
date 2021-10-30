package com.multi.vidulum.risk_management.app;


import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.risk_management.app.queries.GetRiskManagementStatementQuery;
import com.multi.vidulum.risk_management.domain.RiskManagementStatement;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@AllArgsConstructor
public class RiskManagementRestController {

    private final QueryGateway queryGateway;

    @GetMapping("/risk-management/{portfolioId}")
    public RiskManagementDto.RiskManagementStatementJson getRiskManagementStatement(@PathVariable("portfolioId") String portfolioId) {
        GetRiskManagementStatementQuery query = GetRiskManagementStatementQuery.builder()
                .portfolioId(PortfolioId.of(portfolioId))
                .build();

        RiskManagementStatement statement = queryGateway.send(query);
        return toJson(statement);
    }

    private RiskManagementDto.RiskManagementStatementJson toJson(RiskManagementStatement statement) {
        List<RiskManagementDto.AssetRiskManagementStatementJson> assets = statement.getAssetRiskManagementStatements()
                .stream()
                .map(assetRiskManagementStatement -> {
                    List<RiskManagementDto.StopLossJson> stopLosses = assetRiskManagementStatement.getStopLosses().stream()
                            .map(stopLoss -> RiskManagementDto.StopLossJson.builder()
                                    .symbol(stopLoss.getSymbol().getId())
                                    .quantity(stopLoss.getQuantity())
                                    .price(stopLoss.getPrice())
                                    .dateTime(stopLoss.getDateTime())
                                    .isApplicable(stopLoss.isApplicable())
                                    .build())
                            .collect(Collectors.toList());

                    return RiskManagementDto.AssetRiskManagementStatementJson.builder()
                            .ticker(assetRiskManagementStatement.getTicker().getId())
                            .quantity(assetRiskManagementStatement.getQuantity())
                            .stopLosses(stopLosses)
                            .avgPurchasePrice(assetRiskManagementStatement.getAvgPurchasePrice())
                            .currentPrice(assetRiskManagementStatement.getCurrentPrice())
                            .riskMoney(assetRiskManagementStatement.getRiskMoney())
                            .ragStatus(assetRiskManagementStatement.getRagStatus())
                            .pctRiskOfPortfolio(assetRiskManagementStatement.getPctRiskOfPortfolio())
                            .build();
                })
                .collect(Collectors.toList());

        return RiskManagementDto.RiskManagementStatementJson.builder()
                .portfolioId(statement.getPortfolioId().getId())
                .userId(statement.getUserId().getId())
                .name(statement.getName())
                .broker(statement.getBroker().getId())
                .assetRiskManagementStatements(assets)
                .investedBalance(statement.getInvestedBalance())
                .currentValue(statement.getCurrentValue())
                .pctProfit(statement.getPctProfit())
                .profit(statement.getProfit())
                .risk(statement.getRisk())
                .riskPct(statement.getRiskPct())
                .build();
    }
}
