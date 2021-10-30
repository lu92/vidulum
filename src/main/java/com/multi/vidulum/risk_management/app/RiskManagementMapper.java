package com.multi.vidulum.risk_management.app;

import com.multi.vidulum.risk_management.domain.RiskManagementStatement;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class RiskManagementMapper {

    public RiskManagementDto.RiskManagementStatementJson toJson(RiskManagementStatement statement) {
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
                            .currentValue(assetRiskManagementStatement.getCurrentValue())
                            .safeMoney(assetRiskManagementStatement.getSafeMoney())
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
                .safe(statement.getSafe())
                .risk(statement.getRisk())
                .riskPct(statement.getRiskPct())
                .build();
    }

}
