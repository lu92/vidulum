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

    /**
     * Records a trade, and answers with the trade that is now stored (task F1).
     *
     * <p>It used to answer {@code void}, which was survivable while the caller supplied every
     * identifier. It is not survivable now: when the caller sends no {@code originTradeId} the
     * backend mints one, and that value is exactly what they would need to send on a retry.
     */
    @PostMapping("/trades")
    public TradingDto.TradeSummaryJson makeTrade(@RequestBody TradingDto.TradeExecutedJson tradeExecutedJson) {
        MakeTradeCommand command = MakeTradeCommand.builder()
                // Both from the caller, not the payload: a trade used to be recorded under one
                // user's id inside another user's portfolio, and neither was checked.
                .userId(access.currentUser())
                .portfolioId(access.requireOwned(tradeExecutedJson.getPortfolioId()))
                .originTradeId(identityOf(tradeExecutedJson))
                .orderId(orderIdOf(tradeExecutedJson))
                .symbol(Symbol.of(tradeExecutedJson.getSymbol()))
                .side(tradeExecutedJson.getSide())
                .subName(SubName.of(tradeExecutedJson.getSubName()))
                .quantity(tradeExecutedJson.getQuantity())
                .price(tradeExecutedJson.getPrice())
                .fee(new MakeTradeCommand.Fee(
                        tradeExecutedJson.getFee().getExchangeCurrencyFee(),
                        tradeExecutedJson.getFee().getTransactionFee()))
                .originDateTime(momentOf(tradeExecutedJson))
                .build();

        Trade stored = commandGateway.send(command);
        return mapper.toJson(stored);
    }

    /**
     * What the trade is called at its origin.
     *
     * <p>An exchange fill arrives with the exchange's own id, and a CSV export carries the same
     * string — which is what makes re-importing an overlapping range harmless. A hand-entered
     * trade has no such origin but still needs identity: a client that mints one when the form
     * opens and resends it on a retry turns a double-click into one trade.
     *
     * <p>When nothing is sent we mint one. That keeps the column free of nulls, so the unique
     * index needs no sparse variant — but it protects nobody, and saying so is the point: the
     * protection comes from the caller's key, not from ours.
     */
    private static OriginTradeId identityOf(TradingDto.TradeExecutedJson json) {
        String stated = json.getOriginTradeId();
        return stated != null && !stated.isBlank()
                ? OriginTradeId.of(stated)
                : OriginTradeId.generate();
    }

    /**
     * When the trade happened, or when we heard about it.
     *
     * <p>A payload may leave it out — plenty did, and nothing complained, because nothing read it.
     * Now something does: a settled sale is dated by it (task F6), and a fact with no date is one
     * nobody can put in a tax year. Defaulting to the moment of recording is the honest reading of
     * a caller who did not say: we know when we were told, and we do not know more than that.
     */
    private ZonedDateTime momentOf(TradingDto.TradeExecutedJson json) {
        return json.getOriginDateTime() != null
                ? json.getOriginDateTime()
                : ZonedDateTime.now(clock);
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
