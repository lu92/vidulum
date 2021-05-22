package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Side;
import lombok.Builder;
import lombok.Data;

import java.util.List;

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
    public static class AssetSummaryJson {
        private String ticker;
        private String fullName;
        private Money avgPurchasePrice;
        private double quantity;
        private List<String> tags;

        private double pctProfit;
        private Money profit;
        private Money currentPrice;
        private Money currentValue;
    }

    @Data
    @Builder
    public static class TradeExecutedJson {
        private String originTradeId;
        private String portfolioId;
        private String userId;
        private String symbol;
        private Side side;
        private double quantity;
        private Money price;
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
}
