package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.*;
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
        private PortfolioStatus status;
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
        private Price avgPurchasePrice;
        private Quantity quantity;
        private Quantity locked;
        private Quantity free;

        private double pctProfit;
        private Money profit;
        private Price currentPrice;
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
    public static class LockAssetJson {
        private String portfolioId;
        private String ticker;
        private Quantity quantity;
    }

    @Data
    @Builder
    public static class UnlockAssetJson {
        private String portfolioId;
        private String ticker;
        private Quantity quantity;
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
        Price targetPrice;
        Price entryPrice;
        Price stopLoss;
        Quantity quantity;
        Money risk;
        Money reward;
        RiskRewardRatio riskRewardRatio;
        Money value;
        double pctProfit;
    }
}
