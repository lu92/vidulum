package com.multi.vidulum.pnl.app;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
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
        /** Absent when the portfolio summary could not add one up - see {@code ContributionStatus}. */
    private Money netContributions;
        private Money currentValue;
        private Money totalUnrealisedProfit;
        private Double pctUnrealisedProfit;
        private List<PnlPortfolioStatementJson> portfolioStatements;
        private ZonedDateTime dateTime;
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
