package com.multi.vidulum.trading.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.portfolio.app.PortfolioAccess;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.trading.app.commands.trades.execute.MakeTradeCommand;
import com.multi.vidulum.trading.app.queries.GetAllTradesForUserQuery;
import com.multi.vidulum.trading.app.queries.GetTradesForUserInDateRangeQuery;
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
public class TradeRestController {
    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final TradingMapper mapper;
    private final PortfolioAccess access;
    private final Clock clock;

    @PostMapping("/trades")
    public void makeTrade(@RequestBody TradingDto.TradeExecutedJson tradeExecutedJson) {
        MakeTradeCommand command = MakeTradeCommand.builder()
                // Both from the caller, not the payload: a trade used to be recorded under one
                // user's id inside another user's portfolio, and neither was checked.
                .userId(access.currentUser())
                .portfolioId(access.requireOwned(tradeExecutedJson.getPortfolioId()))
                .originTradeId(OriginTradeId.of(tradeExecutedJson.getOriginTradeId()))
                .orderId(orderIdOf(tradeExecutedJson))
                .symbol(Symbol.of(tradeExecutedJson.getSymbol()))
                .side(tradeExecutedJson.getSide())
                .subName(SubName.of(tradeExecutedJson.getSubName()))
                .quantity(tradeExecutedJson.getQuantity())
                .price(tradeExecutedJson.getPrice())
                .fee(new MakeTradeCommand.Fee(
                        tradeExecutedJson.getFee().getExchangeCurrencyFee(),
                        tradeExecutedJson.getFee().getTransactionFee()))
                .originDateTime(tradeExecutedJson.getOriginDateTime())
                .build();

        commandGateway.send(command);
    }

    /**
     * A blank {@code orderId} means the trade was entered by hand — a purchase from a dealer, not
     * a fill from an exchange. Mapped to {@link OrderId#notDefined()} so everything downstream
     * compares a value instead of guarding against null.
     */
    private static OrderId orderIdOf(TradingDto.TradeExecutedJson json) {
        String orderId = json.getOrderId();
        return orderId == null || orderId.isBlank() ? OrderId.notDefined() : OrderId.of(orderId);
    }

    @GetMapping("/trades/{portfolioId}")
    public List<TradingDto.TradeSummaryJson> getAllTrades(@PathVariable("portfolioId") String portfolioId) {
        GetAllTradesForUserQuery query = GetAllTradesForUserQuery.builder()
                .userId(access.currentUser())
                .portfolioId(access.requireOwned(portfolioId))
                .build();
        List<Trade> trades = queryGateway.send(query);
        return trades.stream()
                .map(mapper::toJson)
                .collect(toList());
    }

    @GetMapping("/trades")
    public List<TradingDto.TradeSummaryJson> getTradesInDateRange(
            @RequestParam("from") ZonedDateTime from,
            @RequestParam("to") ZonedDateTime to) {
        GetTradesForUserInDateRangeQuery query = GetTradesForUserInDateRangeQuery.builder()
                .userId(access.currentUser())
                .dateTimeRange(Range.of(from, to))
                .build();
        List<Trade> trades = queryGateway.send(query);
        return trades.stream()
                .map(mapper::toJson)
                .collect(toList());
    }
}
