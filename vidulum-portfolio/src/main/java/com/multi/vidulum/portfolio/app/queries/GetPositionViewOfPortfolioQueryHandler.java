package com.multi.vidulum.portfolio.app.queries;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.api.error.PortfolioNotFoundException;
import com.multi.vidulum.trading.api.TradingModuleApi;
import com.multi.vidulum.trading.api.view.OrderView;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
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
    private final TradingModuleApi tradingApi;

    @Override
    public OpenedPositions query(GetPositionViewOfPortfolioQuery query) {
        Portfolio portfolio = portfolioRepository.findById(query.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(query.getPortfolioId()));

        List<OrderView> openedOrders = tradingApi.openedOrders(query.getPortfolioId());
        List<Position> positions = openedOrders.isEmpty() ? List.of() : fetchOnlyOpenedPositions(portfolio, openedOrders);
        return OpenedPositions.builder()
                .portfolioId(query.getPortfolioId())
                .broker(portfolio.getBroker())
                .positions(positions)
                .build();
    }

    private List<Position> fetchOnlyOpenedPositions(Portfolio portfolio, List<OrderView> openedOrders) {
        Map<Symbol, Position> positionMap = buildPositions(portfolio, openedOrders);
        return new LinkedList<>(positionMap.values());
    }

    private Map<Symbol, Position> buildPositions(Portfolio portfolio, List<OrderView> openedOrders) {
        Map<Ticker, List<OrderView>> sellOrders = openedOrders.stream()
                .filter(order -> Side.SELL.equals(order.side()))
                .collect(groupingBy(order -> order.symbol().getOrigin()));

        return portfolio.getAssets().stream()
                .filter(asset -> sellOrders.containsKey(asset.getTicker()))
                .map(asset -> {
                    List<OrderView> relatedSellOrders = sellOrders.get(asset.getTicker());
                    Symbol assetSymbol = Symbol.of(asset.getTicker(), Ticker.of("USD"));
                    return relatedSellOrders.stream()
                            .filter(order -> order.symbol().getDestination().equals(Ticker.of("USD")))
                            .reduce(
                                    Position.zero(assetSymbol),
                                    (consideredPosition, order) -> {
                                        Money targetValue = consideredPosition.getTargetValue().plus(order.targetPrice().multiply(order.quantity()));
                                        Money entryValue = consideredPosition.getEntryValue().plus(order.stopPrice().multiply(order.quantity()));
                                        Money stopLossValue = consideredPosition.getStopLossValue().plus(order.limitPrice().multiply(order.quantity()));
                                        Quantity quantity = consideredPosition.getQuantity().plus(order.quantity());
                                        return Position.builder()
                                                .symbol(assetSymbol)
                                                .targetPrice(Price.of(targetValue.divide(quantity)))
                                                .entryPrice(Price.of(entryValue.divide(quantity)))
                                                .stopLoss(Price.of(stopLossValue.divide(quantity)))
                                                .quantity(consideredPosition.getQuantity().plus(order.quantity()))
                                                .build();
                                    },
                                    Position::combine);
                })
                .collect(toMap(Position::getSymbol, Function.identity()));
    }
}
