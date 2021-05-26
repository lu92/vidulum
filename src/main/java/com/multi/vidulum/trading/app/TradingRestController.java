package com.multi.vidulum.trading.app;

import com.multi.vidulum.common.OriginTradeId;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.trading.app.commands.MakeTradeCommand;
import com.multi.vidulum.trading.app.queries.GetAllTradesForUserQuery;
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
                .dateTime(ZonedDateTime.now())
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
                .dateTime(trade.getDateTime())
                .build();
    }
}
