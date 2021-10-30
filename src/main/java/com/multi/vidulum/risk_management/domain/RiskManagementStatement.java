package com.multi.vidulum.risk_management.domain;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RiskManagementStatement {
    private PortfolioId portfolioId;
    private UserId userId;
    private String name;
    private Broker broker;
    private List<AssetRiskManagementStatement> assetRiskManagementStatements;
    private Money investedBalance;
    private Money currentValue;
    private double pctProfit;
    private Money profit;
    private Money risk;
    private double riskPct;
}
