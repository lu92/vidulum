package com.multi.vidulum.trading.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.trading.app.commands.CancelOrderCommand;
import com.multi.vidulum.trading.app.commands.MakeTradeCommand;
import com.multi.vidulum.trading.app.commands.PlaceOrderCommand;
import com.multi.vidulum.trading.app.queries.GetAllOpenedOrdersForPortfolioQuery;
import com.multi.vidulum.trading.app.queries.GetAllTradesForUserQuery;
import com.multi.vidulum.trading.app.queries.GetTradesForUserInDateRangeQuery;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.Trade;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.List;

import static java.util.stream.Collectors.toList;

@Slf4j
@RestController
@AllArgsConstructor
public class TradingRestController {
    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    @PostMapping("/trading")
    public void makeTrade(@RequestBody TradingDto.TradeExecutedJson tradeExecutedJson) {
        MakeTradeCommand command = MakeTradeCommand.builder()
                .userId(UserId.of(tradeExecutedJson.getUserId()))
                .portfolioId(PortfolioId.of(tradeExecutedJson.getPortfolioId()))
                .originTradeId(OriginTradeId.of(tradeExecutedJson.getOriginTradeId()))
                .subName(SubName.of(tradeExecutedJson.getSubName()))
                .symbol(Symbol.of(tradeExecutedJson.getSymbol()))
                .side(tradeExecutedJson.getSide())
                .quantity(tradeExecutedJson.getQuantity())
                .price(tradeExecutedJson.getPrice())
                .originDateTime(tradeExecutedJson.getOriginDateTime())
                .build();

        commandGateway.send(command);
    }

    @GetMapping("/trading/{userId}/{portfolioId}")
    public List<TradingDto.TradeSummaryJson> getAllTrades(@PathVariable("userId") String userId, @PathVariable("portfolioId") String portfolioId) {
        GetAllTradesForUserQuery query = GetAllTradesForUserQuery.builder()
                .userId(UserId.of(userId))
                .portfolioId(PortfolioId.of(portfolioId))
                .build();
        List<Trade> trades = queryGateway.send(query);
        return trades.stream()
                .map(this::toJson)
                .collect(toList());
    }

    @GetMapping("/trading")
    public List<TradingDto.TradeSummaryJson> getTradesInDateRange(
            @RequestParam("userId") String userId,
            @RequestParam("from") ZonedDateTime from,
            @RequestParam("to") ZonedDateTime to) {
        GetTradesForUserInDateRangeQuery query = GetTradesForUserInDateRangeQuery.builder()
                .userId(UserId.of(userId))
                .dateTimeRange(Range.of(from, to))
                .build();
        List<Trade> trades = queryGateway.send(query);
        return trades.stream()
                .map(this::toJson)
                .collect(toList());
    }

    @PostMapping("/orders")
    public TradingDto.OrderSummaryJson placeOrder(@RequestParam TradingDto.PlaceOrderJson placeOrderJson) {
        PlaceOrderCommand command = PlaceOrderCommand.builder()
                .originOrderId(OrderId.of(placeOrderJson.getOriginOrderId()))
                .portfolioId(PortfolioId.of(placeOrderJson.getPortfolioId()))
                .symbol(Symbol.of(placeOrderJson.getSymbol()))
                .type(placeOrderJson.getType())
                .side(placeOrderJson.getSide())
                .targetPrice(placeOrderJson.getTargetPrice())
                .entryPrice(placeOrderJson.getEntryPrice())
                .stopLoss(placeOrderJson.getStopLoss())
                .quantity(placeOrderJson.getQuantity())
                .occurredDateTime(placeOrderJson.getOriginDateTime())
                .build();

        Order placedOrder = commandGateway.send(command);
        return toJson(placedOrder);
    }

    @DeleteMapping("/orders/{originOrderId}")
    public TradingDto.OrderSummaryJson cancelOrder(@PathVariable("originOrderId") String originOrderId) {
        CancelOrderCommand command = CancelOrderCommand.builder()
                .originOrderId(OrderId.of(originOrderId))
                .build();
        Order canceledOrder = commandGateway.send(command);
        return toJson(canceledOrder);
    }

    @GetMapping("/orders/{portfolioId}")
    public List<TradingDto.OrderSummaryJson> getAllOpenedOrders(@PathVariable("portfolioId") String portfolioId) {
        GetAllOpenedOrdersForPortfolioQuery query = GetAllOpenedOrdersForPortfolioQuery.builder()
                .portfolioId(PortfolioId.of(portfolioId))
                .build();
        List<Order> orders = queryGateway.send(query);
        return orders.stream()
                .map(this::toJson)
                .collect(toList());
    }

    private TradingDto.TradeSummaryJson toJson(Trade trade) {
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

    private TradingDto.OrderSummaryJson toJson(Order order) {
        return TradingDto.OrderSummaryJson.builder()
                .orderId(order.getOrderId().getId())
                .originOrderId(order.getOriginOrderId().getId())
                .portfolioId(order.getPortfolioId().getId())
                .symbol(order.getSymbol().getId())
                .type(order.getType())
                .side(order.getSide())
                .targetPrice(order.getTargetPrice())
                .entryPrice(order.getEntryPrice())
                .stopLoss(order.getStopLoss())
                .quantity(order.getQuantity())
                .originDateTime(order.getOccurredDateTime())
                .build();
    }
}
