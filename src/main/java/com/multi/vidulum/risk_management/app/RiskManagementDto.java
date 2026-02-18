package com.multi.vidulum.risk_management.app;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.risk_management.domain.RagStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

public final class RiskManagementDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskManagementStatementJson {
        private String portfolioId;
        private String userId;
        private String name;
        private String broker;
        private List<AssetRiskManagementStatementJson> assetRiskManagementStatements;
        private Money investedBalance;
        private Money currentValue;
        private Money profit;
        private Money safe;
        private Money risk;
        private double pctProfit;
        private double riskPct;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetRiskManagementStatementJson {
        private String ticker;
        private Quantity quantity;
        private List<StopLossJson> stopLosses;
        private Price avgPurchasePrice;
        private Money currentValue;
        private Price currentPrice;
        private Money safeMoney;
        private Money riskMoney;
        private RagStatus ragStatus;
        private double pctRiskOfPortfolio;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StopLossJson {
        private String symbol;
        private String originOrderId;
        private Quantity quantity;
        private Price price;
        private boolean isApplicable;
        private ZonedDateTime dateTime;
    }
}
