package com.multi.vidulum.trading.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.trading.app.commands.orders.cancel.CancelOrderCommand;
import com.multi.vidulum.trading.app.commands.orders.create.PlaceOrderCommand;
import com.multi.vidulum.trading.app.commands.orders.execute.ExecuteOrderCommand;
import com.multi.vidulum.trading.app.commands.orders.execute.OrderExecutionSummary;
import com.multi.vidulum.trading.app.commands.trades.execute.MakeTradeCommand;
import com.multi.vidulum.trading.app.queries.GetAllOpenedOrdersForPortfolioQuery;
import com.multi.vidulum.trading.app.queries.GetAllTradesForUserQuery;
import com.multi.vidulum.trading.app.queries.GetTradesForUserInDateRangeQuery;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.Trade;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

import static java.util.stream.Collectors.toList;

@Slf4j
@RestController
@AllArgsConstructor
public class TradingRestController {
    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final TradingMapper mapper;
    private final Clock clock;

    @PostMapping("/trading")
    public void makeTrade(@RequestBody TradingDto.TradeExecutedJson tradeExecutedJson) {
        MakeTradeCommand command = MakeTradeCommand.builder()
                .userId(UserId.of(tradeExecutedJson.getUserId()))
                .portfolioId(PortfolioId.of(tradeExecutedJson.getPortfolioId()))
                .originTradeId(OriginTradeId.of(tradeExecutedJson.getOriginTradeId()))
                .originOrderId(OriginOrderId.notDefined())
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
                .map(mapper::toJson)
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
                .map(mapper::toJson)
                .collect(toList());
    }

    @PostMapping("/orders")
    public TradingDto.OrderSummaryJson placeOrder(@RequestParam TradingDto.PlaceOrderJson placeOrderJson) {
        PlaceOrderCommand command = PlaceOrderCommand.builder()
                .originOrderId(OriginOrderId.of(placeOrderJson.getOriginOrderId()))
                .portfolioId(PortfolioId.of(placeOrderJson.getPortfolioId()))
                .broker(Broker.of(placeOrderJson.getBroker()))
                .symbol(Symbol.of(placeOrderJson.getSymbol()))
                .type(placeOrderJson.getType())
                .side(placeOrderJson.getSide())
                .targetPrice(placeOrderJson.getTargetPrice())
                .stopPrice(placeOrderJson.getStopPrice())
                .limitPrice(placeOrderJson.getLimitPrice())
                .quantity(placeOrderJson.getQuantity())
                .occurredDateTime(placeOrderJson.getOriginDateTime())
                .build();

        Order placedOrder = commandGateway.send(command);
        return mapper.toJson(placedOrder);
    }

    @PutMapping
    public TradingDto.OrderExecutionSummaryJson executeOrder(@RequestParam TradingDto.ExecuteOrderJson executeOrderJson) {
        ExecuteOrderCommand command = ExecuteOrderCommand.builder()
                .originTradeId(OriginTradeId.of(executeOrderJson.getOriginTradeId()))
                .originOrderId(OriginOrderId.of(executeOrderJson.getOriginOrderId()))
                .originDateTime(ZonedDateTime.now(clock))
                .build();
        OrderExecutionSummary summary = commandGateway.send(command);
        return mapper.toJson(summary);
    }

    @DeleteMapping("/orders/{originOrderId}")
    public TradingDto.OrderSummaryJson cancelOrder(@PathVariable("originOrderId") String originOrderId) {
        CancelOrderCommand command = CancelOrderCommand.builder()
                .originOrderId(OriginOrderId.of(originOrderId))
                .build();
        Order canceledOrder = commandGateway.send(command);
        return mapper.toJson(canceledOrder);
    }

    @GetMapping("/orders/{portfolioId}")
    public List<TradingDto.OrderSummaryJson> getAllOpenedOrders(@PathVariable("portfolioId") String portfolioId) {
        GetAllOpenedOrdersForPortfolioQuery query = GetAllOpenedOrdersForPortfolioQuery.builder()
                .portfolioId(PortfolioId.of(portfolioId))
                .build();
        List<Order> orders = queryGateway.send(query);
        return orders.stream()
                .map(mapper::toJson)
                .collect(toList());
    }
}
