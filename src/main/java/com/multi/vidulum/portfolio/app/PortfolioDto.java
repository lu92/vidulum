package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.RiskRewardRatio;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

public class PortfolioDto {

    @Data
    @Builder
    public static class CreateEmptyPortfolioJson {
        private String name;
        private String userId;
        private String broker;
    }

    @Data
    @Builder
    public static class PortfolioSummaryJson {
        private String portfolioId;
        private String userId;
        private String name;
        private String broker;
        private List<AssetSummaryJson> assets;
        private Money investedBalance;
        private Money currentValue;
        private double pctProfit;
        private Money profit;
    }

    @Data
    @Builder
    @EqualsAndHashCode
    public static class AssetSummaryJson {
        private String ticker;
        private String fullName;
        private Money avgPurchasePrice;
        private Quantity quantity;

        private double pctProfit;
        private Money profit;
        private Money currentPrice;
        private Money currentValue;
        private List<String> tags;
    }

    @Data
    @Builder
    public static class DepositMoneyJson {
        private String portfolioId;
        private Money money;
    }

    @Data
    @Builder
    public static class WithdrawMoneyJson {
        private String portfolioId;
        private Money money;
    }

    @Data
    @Builder
    public static class AggregatedPortfolioSummaryJson {
        private String userId;
        private Map<String, List<AssetSummaryJson>> segmentedAssets;
        private List<String> portfolioIds;
        private Money investedBalance;
        private Money currentValue;
        private Money totalProfit;
        private double pctProfit;
    }

    @Data
    @Builder
    public static class OpenedPositionsJson {
        private String portfolioId;
        private List<PositionSummaryJson> positions;
    }

    @Data
    @Builder
    public static class PositionSummaryJson {
        String symbol;
        Money targetPrice;
        Money entryPrice;
        Money stopLoss;
        Quantity quantity;
        Money risk;
        Money reward;
        RiskRewardRatio riskRewardRatio;
        Money value;
        double pctProfit;
    }
}
