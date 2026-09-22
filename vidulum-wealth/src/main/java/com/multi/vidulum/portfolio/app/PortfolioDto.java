package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitStatus;
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

        /**
         * What the portfolio settles in. Required: without it the aggregate cannot say which
         * deposits it accepts, and {@code PortfolioFactory.empty} has nothing to build
         * {@code investedBalance} from.
         */
        private String allowedDepositCurrency;
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

        /**
         * Gain on what is <b>currently held</b>, and how much of the value it speaks for
         * (tasks C3, C4).
         *
         * <p>Named for what it is. The field used to be {@code profit} and was
         * {@code currentValue - investedBalance}, which mixes in gains already realised and
         * collapses to the entire value when {@code investedBalance} is zero — as it is for every
         * portfolio built from an exchange snapshot (C9). Renaming rather than redefining is the
         * point: a client reading {@code profit} would otherwise receive a different quantity
         * under the same name. Realised results belong to the PnL module, not here.
         *
         * <p>Both are {@code null} unless {@code profitStatus} is {@code COMPUTED} — the status
         * says which of the three silences this is. Withheld rather than annotated on purpose: a
         * caveat is something a client can drop, and the misleading number would outlive it.
         */
        private Double pctUnrealisedProfit;
        private Money unrealisedProfit;
        private Double profitCoverage;
        private ProfitStatus profitStatus;
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
        private Double pctUnrealisedProfit;
        private Money unrealisedProfit;

        /**
         * What share of this position has a known cost — {@code null} when it holds nothing.
         *
         * <p>Not withheld at low coverage the way the portfolio total is: here the covered
         * quantity sits right next to {@code quantity} in the same object, so a reader can see
         * that 0.3 of 100 is priced. The total has no such visible pair, which is exactly why it
         * needs the rule.
         */
        private Double coverage;

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

        /**
         * Which order reserves these units. Required, and not a formality: a lock is released by
         * matching this id, so one recorded without it can never be undone — and the summary
         * cannot even be read afterwards.
         */
        private String orderId;

        private Quantity quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnlockAssetJson {
        private String portfolioId;
        private String ticker;

        /** The order whose lock is being released — see {@link LockAssetJson}. */
        private String orderId;

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
        private Money totalUnrealisedProfit;

        /** Same rule as one portfolio — see {@link PortfolioSummaryJson}. */
        private Double pctUnrealisedProfit;
        private Double profitCoverage;
        private ProfitStatus profitStatus;
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
