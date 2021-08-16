package com.multi.vidulum.pnl.app;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;

public class PnlDto {

    @Data
    @Builder
    public static class MakePnlSnapshotJson {
        private String userId;
        private ZonedDateTime from;
        private ZonedDateTime to;
    }

    @Data
    @Builder
    public static class PnlHistoryJson {
        String pnlId;
        String userId;
        List<PnlStatementJson> pnlStatements;
    }

    @Data
    @Builder
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
    public static class PnlTradeDetailsJson {
        private String tradeId;
        private String originTradeId;
        private String portfolioId;
        private String symbol;
        private String subName;
        private Side side;
        private Quantity quantity;
        private Money price;
        private ZonedDateTime originDateTime;
    }
}
