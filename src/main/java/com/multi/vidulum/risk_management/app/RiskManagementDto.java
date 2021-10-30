package com.multi.vidulum.risk_management.app;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.risk_management.domain.RagStatus;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;

public class RiskManagementDto {

    @Data
    @Builder
    public static class RiskManagementStatementJson {
        private String portfolioId;
        private String userId;
        private String name;
        private String broker;
        private List<AssetRiskManagementStatementJson> assetRiskManagementStatements;
        private Money investedBalance;
        private Money currentValue;
        private double pctProfit;
        private Money profit;
        private Money risk;
        private double riskPct;
    }

    @Data
    @Builder
    public static class AssetRiskManagementStatementJson {
        private String ticker;
        private Quantity quantity;
        private List<StopLossJson> stopLosses;
        private Money avgPurchasePrice;
        private Money currentPrice;
        private Money riskMoney;
        private RagStatus ragStatus;
        private double pctRiskOfPortfolio;
    }

    @Data
    @Builder
    public static class StopLossJson {
        private String symbol;
        private Quantity quantity;
        private Money price;
        private boolean isApplicable;
        private ZonedDateTime dateTime;
    }

}
