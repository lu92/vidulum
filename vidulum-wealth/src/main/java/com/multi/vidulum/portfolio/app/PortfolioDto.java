package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class PortfolioDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateEmptyPortfolioJson {
        private String name;
        private String userId;
        private String broker;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class AssetSummaryJson {
        private String ticker;
        private String fullName;

        /**
         * What the known part of the position cost, or {@code null} when the cost is unknown.
         * Carries {@code provenance} so the interface can distinguish "you told us this" from
         * "the exchange reported it".
         */
        private CostBasisJson costBasis;

        private Quantity quantity;
        private Quantity locked;
        private Quantity free;
        private Set<AssetLockJson> activeLocks;

        /**
         * {@code null} when no cost is known. Deliberately not zero: a position transferred in
         * from outside has no profit we can compute, and reporting zero would present a guess as
         * a fact.
         */
        private Double pctProfit;
        private Money profit;
        private Price currentPrice;
        private Money currentValue;
        private List<String> tags;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class CostBasisJson {
        /** How many units this cost covers — may be less than the position holds. */
        private Quantity quantity;
        private Price avgPrice;
        private Provenance provenance;

        public static CostBasisJson from(CostBasis costBasis) {
            return costBasis == null ? null : CostBasisJson.builder()
                    .quantity(costBasis.quantity())
                    .avgPrice(costBasis.avgPrice().withScale(4))
                    .provenance(costBasis.provenance())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetLockJson {
        private String orderId;
        private Quantity quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepositMoneyJson {
        private String portfolioId;
        private Money money;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WithdrawMoneyJson {
        private String portfolioId;
        private Money money;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LockAssetJson {
        private String portfolioId;
        private String ticker;
        private Quantity quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnlockAssetJson {
        private String portfolioId;
        private String ticker;
        private Quantity quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpenedPositionsJson {
        private String portfolioId;
        private List<PositionSummaryJson> positions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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
