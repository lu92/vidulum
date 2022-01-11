package com.multi.vidulum.trading.app;

import com.multi.vidulum.trading.app.commands.orders.execute.OrderExecutionSummary;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.Trade;
import org.springframework.stereotype.Component;

@Component
public final class TradingMapper {

    public TradingDto.TradeSummaryJson toJson(Trade trade) {
        return TradingDto.TradeSummaryJson.builder()
                .tradeId(trade.getTradeId().getId())
                .userId(trade.getUserId().getId())
                .portfolioId(trade.getPortfolioId().getId())
                .originTradeId(trade.getOriginTradeId().getId())
                .subName(trade.getSubName().getName())
                .symbol(trade.getSymbol().getId())
                .side(trade.getSide())
                .quantity(trade.getQuantity())
                .price(trade.getPrice())
                .originDateTime(trade.getDateTime())
                .build();
    }

    public TradingDto.OrderSummaryJson toJson(Order order) {
        return TradingDto.OrderSummaryJson.builder()
                .orderId(order.getOrderId().getId())
                .originOrderId(order.getOriginOrderId().getId())
                .portfolioId(order.getPortfolioId().getId())
                .symbol(order.getSymbol().getId())
                .type(order.getType())
                .side(order.getSide())
                .status(order.getStatus())
                .targetPrice(order.getTargetPrice())
                .stopPrice(order.getStopPrice())
                .limitPrice(order.getLimitPrice())
                .quantity(order.getQuantity())
                .originDateTime(order.getOccurredDateTime())
                .build();
    }

    public TradingDto.OrderExecutionSummaryJson toJson(OrderExecutionSummary summary) {
        return TradingDto.OrderExecutionSummaryJson.builder()
                .originOrderId(summary.getOriginOrderId().getId())
                .originTradeId(summary.getOriginTradeId().getId())
                .symbol(summary.getSymbol().getId())
                .type(summary.getType())
                .side(summary.getSide())
                .quantity(summary.getQuantity())
                .profit(summary.getProfit())
                .build();
    }
}
