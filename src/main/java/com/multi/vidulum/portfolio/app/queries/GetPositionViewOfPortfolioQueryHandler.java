package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.TradingRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.trading.domain.OpenedPositions;
import com.multi.vidulum.trading.domain.Position;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

@Component
@AllArgsConstructor
public class GetPositionViewOfPortfolioQueryHandler implements QueryHandler<GetPositionViewOfPortfolioQuery, OpenedPositions> {

    private final DomainPortfolioRepository portfolioRepository;
    private final TradingRestClient tradingRestClient;

    @Override
    public OpenedPositions query(GetPositionViewOfPortfolioQuery query) {
        Portfolio portfolio = portfolioRepository.findById(query.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(query.getPortfolioId()));

        List<TradingDto.OrderSummaryJson> openedOrders = tradingRestClient.getOpenedOrders(query.getPortfolioId());
        List<Position> positions = openedOrders.isEmpty() ? List.of() : fetchOnlyOpenedPositions(portfolio, openedOrders);
        return OpenedPositions.builder()
                .portfolioId(query.getPortfolioId())
                .broker(portfolio.getBroker())
                .positions(positions)
                .build();
    }

    private List<Position> fetchOnlyOpenedPositions(Portfolio portfolio, List<TradingDto.OrderSummaryJson> openedOrders) {
        Map<Symbol, Position> positionMap = buildPositions(portfolio, openedOrders);
        return new LinkedList<>(positionMap.values());
    }

    private Map<Symbol, Position> buildPositions(Portfolio portfolio, List<TradingDto.OrderSummaryJson> openedOrders) {
        Map<Ticker, List<TradingDto.OrderSummaryJson>> sellOrders = openedOrders.stream()
                .filter(orderSummaryJson -> Side.SELL.equals(orderSummaryJson.getSide()))
                .collect(groupingBy(orderSummaryJson -> {
                    Symbol symbol = Symbol.of(orderSummaryJson.getSymbol());
                    return symbol.getOrigin();
                }));

        return portfolio.getAssets().stream()
                .filter(asset -> sellOrders.containsKey(asset.getTicker()))
                .map(asset -> {
                    List<TradingDto.OrderSummaryJson> relatedSellOrders = sellOrders.get(asset.getTicker());
                    Symbol assetSymbol = Symbol.of(asset.getTicker(), Ticker.of("USD"));
                    return relatedSellOrders.stream()
                            .filter(orderSummaryJson -> Symbol.of(orderSummaryJson.getSymbol()).getDestination().equals(Ticker.of("USD")))
                            .reduce(
                                    Position.zero(assetSymbol),
                                    (consideredPosition, orderSummaryJson) -> {
                                        Money targetValue = consideredPosition.getTargetValue().plus(orderSummaryJson.getTargetPrice().multiply(orderSummaryJson.getQuantity()));
                                        Money entryValue = consideredPosition.getEntryValue().plus(orderSummaryJson.getStopPrice().multiply(orderSummaryJson.getQuantity()));
                                        Money stopLossValue = consideredPosition.getStopLossValue().plus(orderSummaryJson.getLimitPrice().multiply(orderSummaryJson.getQuantity()));
                                        Quantity quantity = consideredPosition.getQuantity().plus(orderSummaryJson.getQuantity());
                                        return Position.builder()
                                                .symbol(assetSymbol)
                                                .targetPrice(Price.of(targetValue.divide(quantity)))
                                                .entryPrice(Price.of(entryValue.divide(quantity)))
                                                .stopLoss(Price.of(stopLossValue.divide(quantity)))
                                                .quantity(consideredPosition.getQuantity().plus(orderSummaryJson.getQuantity()))
                                                .build();
                                    },
                                    Position::combine);
                })
                .collect(toMap(Position::getSymbol, Function.identity()));
    }
}
