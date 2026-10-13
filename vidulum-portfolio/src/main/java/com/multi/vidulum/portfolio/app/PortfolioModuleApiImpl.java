package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.api.view.AggregatedPortfolioView;
import com.multi.vidulum.portfolio.api.PortfolioModuleApi;
import com.multi.vidulum.portfolio.api.view.PortfolioView;
import com.multi.vidulum.portfolio.api.view.PositionView;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommand;
import com.multi.vidulum.portfolio.app.commands.lock.LockAssetCommand;
import com.multi.vidulum.portfolio.app.commands.unlock.UnlockAssetCommand;
import com.multi.vidulum.portfolio.app.queries.GetAggregatedPortfolioQuery;
import com.multi.vidulum.portfolio.app.queries.GetPortfolioQuery;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The portfolio module answering its own contract.
 *
 * <p>Package-private, which is as close as Java gets to {@code internal}: outside this artifact
 * nothing can name it, and inside it nothing needs to. The predecessor,
 * {@code PortfolioRestClientImpl}, was public and lived in {@code vidulum-app}.
 *
 * <p>It goes through {@link CommandGateway} and {@link QueryGateway} rather than through the
 * handlers themselves, and that one choice is why it can live here at all. An injected handler is
 * a dependency on the <b>middle</b> of this module; a gateway is a dependency on its entrance.
 * With handlers, the implementation had to sit wherever every bean definition was visible — which
 * is the application.
 *
 * <p>Views are built from {@link PortfolioSummaryMapper}'s output rather than from the aggregate
 * directly. Valuation is involved — the denomination chain, unpriced positions, contribution
 * coverage — and computing it twice would eventually mean computing it differently. The web DTO is
 * therefore an internal detail of this class, which is fine: both are private to the module.
 */
@Component
class PortfolioModuleApiImpl implements PortfolioModuleApi {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final PortfolioSummaryMapper portfolioSummaryMapper;

    /**
     * Both gateways arrive {@code @Lazy}, and they have to.
     *
     * <p>A gateway is built by collecting every handler in the context, and one of those handlers —
     * {@code MakePnlSnapshotCommandHandler} — asks for this contract. Injecting the gateway eagerly
     * therefore closes a cycle: gateway needs the handler, handler needs this, this needs the
     * gateway. Spring reports it as {@code BeanCurrentlyInCreationException} at startup.
     *
     * <p>Injecting the handlers directly, as the predecessor in {@code vidulum-app} did, dodges the
     * cycle by never touching the gateway — at the price of depending on the middle of this module
     * and of only being constructible where every bean definition is visible. A proxy that resolves
     * on first call is the smaller concession, and it is the one the test double this class replaced
     * had already made.
     */
    PortfolioModuleApiImpl(@Lazy CommandGateway commandGateway,
                           @Lazy QueryGateway queryGateway,
                           PortfolioSummaryMapper portfolioSummaryMapper) {
        this.commandGateway = commandGateway;
        this.queryGateway = queryGateway;
        this.portfolioSummaryMapper = portfolioSummaryMapper;
    }

    @Override
    public PortfolioId open(String name, UserId owner, Broker broker, Currency allowedDepositCurrency) {
        Portfolio portfolio = commandGateway.send(
                CreateEmptyPortfolioCommand.builder()
                        .portfolioId(PortfolioId.generate())
                        .name(name)
                        .userId(owner)
                        .broker(broker)
                        .allowedDepositCurrency(allowedDepositCurrency)
                        .build());
        return portfolio.getPortfolioId();
    }

    @Override
    public void lock(PortfolioId portfolioId, Ticker ticker, OrderId orderId, Quantity quantity) {
        commandGateway.send(
                LockAssetCommand.builder()
                        .portfolioId(portfolioId)
                        .ticker(ticker)
                        .orderId(orderId)
                        .quantity(quantity)
                        .build());
    }

    @Override
    public void unlock(PortfolioId portfolioId, Ticker ticker, OrderId orderId, Quantity quantity) {
        commandGateway.send(
                UnlockAssetCommand.builder()
                        .portfolioId(portfolioId)
                        .ticker(ticker)
                        .orderId(orderId)
                        .quantity(quantity)
                        .build());
    }

    @Override
    public PortfolioView get(PortfolioId portfolioId) {
        Portfolio portfolio = portfolio(portfolioId);
        return view(portfolioSummaryMapper.map(portfolio, portfolio.getAllowedDepositCurrency()));
    }

    @Override
    public PortfolioView get(PortfolioId portfolioId, Currency valuedIn) {
        return view(portfolioSummaryMapper.map(portfolio(portfolioId), valuedIn));
    }

    @Override
    public AggregatedPortfolioView aggregate(UserId owner, Currency valuedIn) {
        AggregatedPortfolio aggregated = queryGateway.send(
                GetAggregatedPortfolioQuery.builder().userId(owner).build());
        return view(portfolioSummaryMapper.map(aggregated, valuedIn));
    }

    private Portfolio portfolio(PortfolioId portfolioId) {
        return queryGateway.send(GetPortfolioQuery.builder().portfolioId(portfolioId).build());
    }

    private static PortfolioView view(PortfolioDto.PortfolioSummaryJson summary) {
        return new PortfolioView(
                PortfolioId.of(summary.getPortfolioId()),
                UserId.of(summary.getUserId()),
                summary.getName(),
                Broker.of(summary.getBroker()),
                summary.getCurrentValue(),
                summary.getNetContributions(),
                summary.getUnrealisedProfit(),
                summary.getPctUnrealisedProfit(),
                summary.getAssets().stream().map(PortfolioModuleApiImpl::position).toList());
    }

    private static PositionView position(PortfolioDto.AssetSummaryJson asset) {
        return new PositionView(
                Ticker.of(asset.getTicker()),
                SubName.of(asset.getSubName()),
                asset.getQuantity(),
                asset.getFree(),
                asset.getCurrentPrice(),
                asset.getCurrentValue(),
                avgPurchasePrice(asset));
    }

    /** Absent for a position whose cost nobody has stated and no trade implies (task C8). */
    private static Price avgPurchasePrice(PortfolioDto.AssetSummaryJson asset) {
        return asset.getCostBasis() == null ? null : asset.getCostBasis().getAvgPrice();
    }

    private static AggregatedPortfolioView view(PortfolioDto.AggregatedPortfolioSummaryJson summary) {
        return new AggregatedPortfolioView(
                UserId.of(summary.getUserId()),
                summary.getPortfolioIds().stream().map(PortfolioId::of).toList(),
                summary.getCurrentValue(),
                summary.getNetContributions(),
                summary.getTotalUnrealisedProfit(),
                summary.getPctUnrealisedProfit());
    }
}
