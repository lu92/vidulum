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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MakePnlSnapshotJson {
        private String userId;
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
        private Money investedBalance;
        private Money currentValue;
        private Money totalProfit;
        private double pctProfit;
        private List<PnlPortfolioStatementJson> portfolioStatements;
        private ZonedDateTime dateTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PnlPortfolioStatementJson {
        private String portfolioId;
        private Money investedBalance;
        private Money currentValue;
        private Money totalProfit;
        private double pctProfit;
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
