package com.multi.vidulum.trading.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.trading.app.commands.orders.cancel.CancelOrderCommand;
import com.multi.vidulum.trading.app.commands.orders.create.PlaceOrderCommand;
import com.multi.vidulum.trading.app.commands.orders.execute.ExecuteOrderCommand;
import com.multi.vidulum.trading.app.commands.orders.execute.OrderExecutionSummary;
import com.multi.vidulum.trading.app.queries.GetAllOpenedOrdersForPortfolioQuery;
import com.multi.vidulum.trading.domain.Order;
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
public class OrderRestController {
    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final TradingMapper mapper;
    private final Clock clock;

    @PostMapping("/orders")
    public TradingDto.OrderSummaryJson placeOrder(@RequestParam TradingDto.PlaceOrderJson placeOrderJson) {
        PlaceOrderCommand command = PlaceOrderCommand.builder()
                .orderId(OrderId.generate())
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

    @PutMapping("/orders")
    public TradingDto.OrderExecutionSummaryJson executeOrder(@RequestParam TradingDto.ExecuteOrderJson executeOrderJson) {
        ExecuteOrderCommand command = ExecuteOrderCommand.builder()
                .originTradeId(OriginTradeId.of(executeOrderJson.getOriginTradeId()))
                .originOrderId(OriginOrderId.of(executeOrderJson.getOriginOrderId()))
                .quantity(executeOrderJson.getQuantity())
                .price(executeOrderJson.getPrice())
                .exchangeCurrencyFee(executeOrderJson.getExchangeCurrencyFee())
                .transactionFee(executeOrderJson.getTransactionFee())
                .originDateTime(ZonedDateTime.now(clock))
                .build();
        OrderExecutionSummary summary = commandGateway.send(command);
        return mapper.toJson(summary);
    }

    @DeleteMapping("/orders/{orderId}")
    public TradingDto.OrderSummaryJson cancelOrder(@PathVariable("orderId") String orderId) {
        CancelOrderCommand command = CancelOrderCommand.builder()
                .orderId(OrderId.of(orderId))
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
