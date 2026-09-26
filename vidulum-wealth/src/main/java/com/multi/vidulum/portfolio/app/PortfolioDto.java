package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionStatus;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitStatus;
import com.multi.vidulum.portfolio.domain.portfolio.RealisedStatus;
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
        /**
         * What the owner has put in, net of what they have taken out, and how much of the ledger
         * that figure speaks for (task C9).
         *
         * <p>Replaces {@code investedBalance}, which only deposits and withdrawals ever moved — so
         * a portfolio built from an exchange snapshot reported {@code 0} beside six figures of
         * holdings. Renamed rather than redefined: a client reading {@code investedBalance} would
         * otherwise receive a different quantity under the same name.
         *
         * <p>{@code null} unless {@code contributionStatus} is {@code COMPUTED}; the status says
         * which silence this is. <b>Not a cost basis</b> — moving an asset in from elsewhere is a
         * contribution to this portfolio at its arrival value, not a purchase at that price.
         */
        private Money netContributions;
        private Double contributionCoverage;
        private ContributionStatus contributionStatus;

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

        /**
         * How much the owner's wealth has changed since they started — value now, less what they
         * put in (task C5).
         *
         * <p>A different question from {@code unrealisedProfit}, and the one most owners actually
         * ask. Profit needs a purchase price, so a holding transferred in from another exchange
         * cannot take part in it: on a real OKX account 92% of the value has no known cost and the
         * result is withheld, leaving the owner with no number at all. This measure needs only two
         * things we do have — what the portfolio is worth, and what went into it — so the unpriced
         * part counts in full.
         *
         * <p>It does <b>not</b> say the trades were good. Wealth can grow while every decision
         * lagged the market; that judgement stays with {@code unrealisedProfit}, and the two must
         * never be added together.
         *
         * <p>Measured since inception rather than over a window, because that is what the ledger
         * can currently support honestly: every entry is dated, but there is no stored valuation
         * to compare against for an arbitrary start date. Windows arrive with the PnL history.
         *
         * <p>{@code null} exactly when {@code netContributions} is — this is derived from it, so
         * the reason for the silence is in {@code contributionStatus} and is not repeated here.
         * {@code pctWealthChange} is additionally {@code null} when nothing is left in the ledger
         * to divide by: everything taken back out leaves a change with no meaningful base.
         */
        private Money wealthChange;
        private Double pctWealthChange;

        /**
         * What sales have actually made, and how much of them the figure speaks for (task F6).
         *
         * <p>{@code unrealisedProfit} answers for what is <b>still held</b>, so an owner who
         * bought at 40 000, sold at 60 000 and now holds cash used to see nothing: there is no
         * position left to carry a gain. It is also the only figure here a tax office recognises —
         * settled, not paper.
         *
         * <p>Distinct from {@code wealthChange} (C5), which mixes closed and open positions and
         * therefore cannot answer "how much did I make on what I sold".
         *
         * <p>{@code null} unless {@code realisedStatus} is {@code COMPUTED}. Selling units whose
         * cost nobody knows is a real event with no computable result (task C7), and zero there
         * would claim the entire proceeds as profit.
         */
        private Money realisedProfit;
        private Double realisedCoverage;
        private RealisedStatus realisedStatus;
    }

    /** Body of {@code POST /portfolio/asset/cost} (task C8). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateCostJson {
        private String portfolioId;
        private String ticker;

        /** Which position of that ticker — {@code traded}, {@code transferred-in} (task C15). */
        private String subName;

        /** Per unit, in the currency it was paid in; not converted on the way in. */
        private Price avgPrice;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class AssetSummaryJson {
        private String ticker;

        /**
         * Which position of this ticker this row is — {@code traded}, {@code transferred-in} or
         * {@code none} for cash (task C15).
         *
         * <p>Without it a reader gets two rows called "BTC" and nothing to tell them apart, which
         * is what a portfolio onboarded from an exchange always produces: one part the exchange
         * priced, one that arrived from elsewhere with no price at all. The split C2 introduced
         * lived in the model, the database and the merge rules, and stopped at this boundary — so
         * the only way to identify a row was to guess from whether it had a cost, and that guess
         * breaks the moment the owner supplies one.
         */
        private String subName;

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

        /**
         * Whether this row is waiting for the owner to say what it cost (task C8).
         *
         * <p>Derived rather than stored — it is {@code coverage < 1} — but stated in the payload
         * so an interface has a task to show instead of a reader having to infer one from a
         * number. This is what turns "unknown" from a permanent property of the data into
         * something somebody can finish: the result stays withheld (C3) and a sale settles nothing
         * computable (C7) until it is answered, and at a tax office that gap is money.
         */
        private boolean awaitingCost;

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

        /** Same rule as one portfolio — see {@link PortfolioSummaryJson}. */
        private Money netContributions;
        private Double contributionCoverage;
        private ContributionStatus contributionStatus;

        private Money currentValue;
        private Money totalUnrealisedProfit;

        /** Same rule as one portfolio — see {@link PortfolioSummaryJson}. */
        private Double pctUnrealisedProfit;
        private Double profitCoverage;
        private ProfitStatus profitStatus;

        /** Same rule as one portfolio — see {@link PortfolioSummaryJson}. */
        private Money wealthChange;
        private Double pctWealthChange;

        /** Same rule as one portfolio — see {@link PortfolioSummaryJson}. */
        private Money realisedProfit;
        private Double realisedCoverage;
        private RealisedStatus realisedStatus;
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
