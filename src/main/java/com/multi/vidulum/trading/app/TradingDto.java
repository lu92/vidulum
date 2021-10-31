package com.multi.vidulum.trading.app;

import com.multi.vidulum.common.*;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

public class TradingDto {

    @Data
    @Builder
    public static class TradeExecutedJson {
        private String originTradeId;
        private String portfolioId;
        private String userId;
        private String symbol;
        private String subName;
        private Side side;
        private Quantity quantity;
        private Money price;
        private ZonedDateTime originDateTime;
    }

    @Data
    @Builder
    public static class TradeSummaryJson {
        private String tradeId;
        private String userId;
        private String portfolioId;
        private String originTradeId;
        private String subName;
        private String symbol;
        private Side side;
        private Quantity quantity;
        private Money price;
        private ZonedDateTime originDateTime;
    }

    @Data
    @Builder
    public static class PlaceOrderJson {
        private String originOrderId;
        private String portfolioId;
        private String symbol;
        private OrderType type;
        private Side side;
        private Money targetPrice;
        private Money entryPrice;
        private Money stopLoss;
        private Quantity quantity;
        private ZonedDateTime originDateTime;
    }

    @Data
    @Builder
    public static class ExecuteOrderJson {
        private String originOrderId;
        private String originTradeId;
        private ZonedDateTime originDateTime;
    }

    @Data
    @Builder
    public static class OrderExecutionSummaryJson {
        private String originOrderId;
        private String originTradeId;
        private String symbol;
        private OrderType type;
        private Side side;
        private Quantity quantity;
        private Money profit;
    }

    @Data
    @Builder
    public static class OrderSummaryJson {
        private String orderId;
        private String originOrderId;
        private String portfolioId;
        private String symbol;
        private OrderType type;
        private Side side;
        private Status status;
        private Money targetPrice;
        private Money entryPrice;
        private Money stopLoss;
        private Quantity quantity;
        private ZonedDateTime originDateTime;
    }
}
