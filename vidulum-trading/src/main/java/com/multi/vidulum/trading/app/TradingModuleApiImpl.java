package com.multi.vidulum.trading.app;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Range;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.trading.api.TradingModuleApi;
import com.multi.vidulum.trading.api.view.OrderView;
import com.multi.vidulum.trading.api.view.TradeView;
import com.multi.vidulum.trading.app.queries.GetAllOpenedOrdersForPortfolioQuery;
import com.multi.vidulum.trading.app.queries.GetTradesForUserInDateRangeQuery;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.Trade;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * The trading module answering its own contract.
 *
 * <p>Package-private, and through {@link QueryGateway} rather than through the query handlers
 * themselves — the same two choices as {@code PortfolioModuleApiImpl}, for the same reasons: a
 * handler is a dependency on the middle of this module, and nothing outside this artifact needs to
 * name the implementation.
 *
 * <p>The predecessor, {@code TradingRestClientAdapter}, lived in <b>portfolio's</b> infrastructure
 * and injected trading's handlers directly, which is why risk and pnl had to import a port out of
 * the portfolio package to read trading at all.
 */
@Component
class TradingModuleApiImpl implements TradingModuleApi {

    private final QueryGateway queryGateway;

    /**
     * {@code @Lazy} for the cycle the portfolio contract already ran into: a gateway is built from
     * every handler in the context, and {@code GetPositionViewOfPortfolioQueryHandler} — itself a
     * query handler — asks for this contract. Eager injection closes the loop and Spring reports
     * {@code BeanCurrentlyInCreationException} at startup.
     */
    TradingModuleApiImpl(@Lazy QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @Override
    public List<OrderView> openedOrders(PortfolioId portfolioId) {
        List<Order> orders = queryGateway.send(
                GetAllOpenedOrdersForPortfolioQuery.builder().portfolioId(portfolioId).build());
        return orders.stream().map(TradingModuleApiImpl::view).toList();
    }

    @Override
    public List<TradeView> tradesInRange(UserId owner, Range<ZonedDateTime> window) {
        List<Trade> trades = queryGateway.send(
                GetTradesForUserInDateRangeQuery.builder().userId(owner).dateTimeRange(window).build());
        return trades.stream().map(TradingModuleApiImpl::view).toList();
    }

    private static OrderView view(Order order) {
        return new OrderView(
                order.getOriginOrderId(),
                order.getSymbol(),
                order.getParameters().side(),
                order.getParameters().quantity(),
                order.getParameters().limitPrice(),
                order.getParameters().stopPrice(),
                order.getParameters().targetPrice(),
                order.getOccurredDateTime());
    }

    private static TradeView view(Trade trade) {
        return new TradeView(
                trade.getTradeId(),
                trade.getOriginTradeId(),
                trade.getPortfolioId(),
                trade.getSymbol(),
                trade.getSubName(),
                trade.getSide(),
                trade.getQuantity(),
                trade.getPrice(),
                trade.getDateTime());
    }
}
