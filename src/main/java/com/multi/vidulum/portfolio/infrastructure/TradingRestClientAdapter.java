package com.multi.vidulum.portfolio.infrastructure;

import com.multi.vidulum.portfolio.domain.TradingRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.app.TradingDto;
import com.multi.vidulum.trading.app.queries.GetAllOpenedOrdersForPortfolioQuery;
import com.multi.vidulum.trading.app.queries.GetAllOpenedOrdersForPortfolioQueryHandler;
import com.multi.vidulum.trading.domain.Order;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.stream.Collectors.toList;

@Component
@AllArgsConstructor
public class TradingRestClientAdapter implements TradingRestClient {

    private final GetAllOpenedOrdersForPortfolioQueryHandler getAllOpenedOrdersForPortfolioQueryHandler;

    @Override
    public List<TradingDto.OrderSummaryJson> getOpenedOrders(PortfolioId portfolioId) {
        List<Order> orders = getAllOpenedOrdersForPortfolioQueryHandler.query(
                GetAllOpenedOrdersForPortfolioQuery.builder()
                        .portfolioId(PortfolioId.of(portfolioId.getId()))
                        .build()
        );

        return orders.stream()
                .map(this::toJson)
                .collect(toList());
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
