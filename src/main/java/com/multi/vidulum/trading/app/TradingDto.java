package com.multi.vidulum.trading.app;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Side;
import lombok.Builder;
import lombok.Data;

public class TradingDto {

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
}
