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

import java.util.ArrayList;
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

        if (openedOrders.isEmpty()) {
            return OpenedPositions.builder()
                    .portfolioId(query.getPortfolioId())
                    .positions(List.of())
                    .build();
        }
        Map<Symbol, Position> positionMap = buildPositions(portfolio, openedOrders);

        return OpenedPositions.builder()
                .portfolioId(query.getPortfolioId())
                .positions(new ArrayList<>(positionMap.values()))
                .build();
    }

    private Map<Symbol, Position> buildPositions(Portfolio portfolio, List<TradingDto.OrderSummaryJson> openedOrders) {
        Map<Ticker, List<TradingDto.OrderSummaryJson>> sellOrders = openedOrders.stream()
                .filter(orderSummaryJson -> Side.SELL.equals(orderSummaryJson.getSide()))
                .collect(groupingBy(orderSummaryJson -> {
                    Symbol symbol = Symbol.of(orderSummaryJson.getSymbol());
                    return symbol.getOrigin();
                }));

//        sellOrders.keySet().stream().map(ticker -> {
//            return Position.builder().build();
//        }).collect(Collectors.toList())

        Map<Symbol, Position> positionMap = portfolio.getAssets().stream()
                .filter(asset -> sellOrders.containsKey(asset.getTicker()))
                .map(asset -> {

                    List<TradingDto.OrderSummaryJson> relatedSellOrders = sellOrders.get(asset.getTicker());

                    Symbol assetSymbol = Symbol.of(asset.getTicker(), Ticker.of("USD"));

                    Position result = relatedSellOrders.stream()
                            .filter(orderSummaryJson -> Symbol.of(orderSummaryJson.getSymbol()).getDestination().equals(Ticker.of("USD")))
                            .reduce(
                                    Position.builder()
                                            .symbol(assetSymbol)
                                            .targetPrice(Money.zero("USD"))
                                            .entryPrice(Money.zero("USD"))
                                            .stopLoss(Money.zero("USD"))
                                            .quantity(Quantity.zero())
                                            .build(),
                                    (consideredPosition, orderSummaryJson) -> {
                                        Money targetPrice = consideredPosition.getTargetValue().plus(orderSummaryJson.getTargetPrice().multiply(orderSummaryJson.getQuantity().getQty()));
                                        Money entryPrice = consideredPosition.getEntryValue().plus(orderSummaryJson.getEntryPrice().multiply(orderSummaryJson.getQuantity().getQty()));
                                        Money stopLoss = consideredPosition.getStopLossValue().plus(orderSummaryJson.getStopLoss().multiply(orderSummaryJson.getQuantity().getQty()));
                                        Quantity quantity = consideredPosition.getQuantity().plus(orderSummaryJson.getQuantity());

                                        return Position.builder()
                                                .symbol(assetSymbol)
                                                .targetPrice(targetPrice.divide(quantity.getQty()))
                                                .entryPrice(entryPrice.divide(quantity.getQty()))
                                                .stopLoss(stopLoss.divide(quantity.getQty()))
                                                .quantity(consideredPosition.getQuantity().plus(orderSummaryJson.getQuantity()))
                                                .build();
                                    },
                                    Position::combine);

                    return result;
                })
                .collect(toMap(Position::getSymbol, Function.identity()));

        return positionMap;
    }
}
