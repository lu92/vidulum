package com.multi.vidulum.trading.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
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
    private final Clock clock;

    @PostMapping("/trades")
    public void makeTrade(@RequestBody TradingDto.TradeExecutedJson tradeExecutedJson) {
        MakeTradeCommand command = MakeTradeCommand.builder()
                .userId(UserId.of(tradeExecutedJson.getUserId()))
                .portfolioId(PortfolioId.of(tradeExecutedJson.getPortfolioId()))
                .originTradeId(OriginTradeId.of(tradeExecutedJson.getOriginTradeId()))
                .orderId(OrderId.of(tradeExecutedJson.getOrderId()))
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

    @GetMapping("/trades/userId={userId}/{portfolioId}")
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

    @GetMapping("/trades")
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
}
