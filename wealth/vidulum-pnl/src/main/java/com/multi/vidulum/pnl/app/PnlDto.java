package com.multi.vidulum.pnl.app;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.pnl.domain.ValuationBasis;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

public class PnlDto {

    /**
     * Answer of {@code GET /pnl/wealth-change} (task C14).
     *
     * @param measuredFrom when the valuation that answered was actually taken — a daily record
     *                     rarely lands on the requested instant, and the reader is entitled to
     *                     know which day it got
     */
    public record WealthChangeJson(
            java.time.ZonedDateTime since,
            java.time.ZonedDateTime measuredFrom,
            com.multi.vidulum.common.Money change,
            Double pct,
            String status) {
    }


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MakePnlSnapshotJson {
        private ZonedDateTime from;
        private ZonedDateTime to;

        /** Optional; the configured default is used when absent (task C17). */
        private String valuationCurrency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PnlHistoryJson {
        String pnlId;
        String userId;
        List<PnlStatementJson> pnlStatements;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PnlStatementJson {

        /**
         * What this statement is denominated in, and what that currency was worth when it was
         * taken (task C17).
         *
         * <p>Reported rather than left implicit, because a figure whose currency the reader has to
         * assume is a figure they will assume wrongly one day. {@code null} on statements written
         * before C17 — they genuinely cannot say.
         */
        private ValuationBasisJson basis;

        /** Absent when the portfolio summary could not add one up - see {@code ContributionStatus}. */
    private Money netContributions;
        private Money currentValue;
        private Money totalUnrealisedProfit;
        private Double pctUnrealisedProfit;
        private List<PnlPortfolioStatementJson> portfolioStatements;
        private ZonedDateTime dateTime;
    }

    /**
     * The currency a statement is in, plus the rate that lets a reader undo the conversion.
     *
     * <p>{@code rate} and its companions are absent when the statement is in the pivot currency
     * itself, or when no rate was held at the time — see {@code ValuationBasis}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValuationBasisJson {
        private String currency;
        private Price rate;
        private ZonedDateTime rateAsOf;
        private String rateSource;

        public static ValuationBasisJson from(ValuationBasis basis) {
            if (basis == null) {
                return null;
            }
            return basis.knownRate()
                    .map(rate -> new ValuationBasisJson(
                            basis.currency().getId(), rate.rate(), rate.asOf(), rate.source().id()))
                    .orElseGet(() -> new ValuationBasisJson(
                            basis.currency().getId(), null, null, null));
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PnlPortfolioStatementJson {
        private String portfolioId;
        /** Absent when the portfolio summary could not add one up - see {@code ContributionStatus}. */
    private Money netContributions;
        private Money currentValue;
        private Money totalUnrealisedProfit;
        private Double pctUnrealisedProfit;
        private List<PnlTradeDetailsJson> executedTrades;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PnlTradeDetailsJson {
        private String tradeId;
        private String originTradeId;
        private String portfolioId;
        private String symbol;
        private String subName;
        private Side side;
        private Quantity quantity;
        private Price price;
        private ZonedDateTime originDateTime;
    }
}
