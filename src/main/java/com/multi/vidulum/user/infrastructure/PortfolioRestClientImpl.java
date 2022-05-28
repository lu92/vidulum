package com.multi.vidulum.user.infrastructure;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.AggregatedPortfolio;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommand;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommandHandler;
import com.multi.vidulum.portfolio.app.commands.lock.LockAssetCommand;
import com.multi.vidulum.portfolio.app.commands.lock.LockAssetCommandHandler;
import com.multi.vidulum.portfolio.app.commands.unlock.UnlockAssetCommand;
import com.multi.vidulum.portfolio.app.commands.unlock.UnlockAssetCommandHandler;
import com.multi.vidulum.portfolio.app.queries.*;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.user.domain.PortfolioRestClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class PortfolioRestClientImpl implements PortfolioRestClient {

    private final CreateEmptyPortfolioCommandHandler createEmptyPortfolioCommandHandler;
    private final LockAssetCommandHandler lockAssetCommandHandler;
    private final UnlockAssetCommandHandler unlockAssetCommandHandler;
    private final GetAggregatedPortfolioQueryHandler getAggregatedPortfolioQueryHandler;
    private final GetPortfolioQueryHandler getPortfolioQueryHandler;
    private final PortfolioSummaryMapper portfolioSummaryMapper;

    @Override
    public PortfolioId createPortfolio(String name, UserId userId, Broker broker, Currency allowedDepositCurrency) {
        CreateEmptyPortfolioCommand command = CreateEmptyPortfolioCommand.builder()
                .portfolioId(PortfolioId.generate())
                .name(name)
                .userId(userId)
                .broker(broker)
                .allowedDepositCurrency(allowedDepositCurrency)
                .build();
        Portfolio portfolio = createEmptyPortfolioCommandHandler.handle(command);
        return portfolio.getPortfolioId();
    }

    @Override
    public void lockAsset(PortfolioId portfolioId, Ticker ticker, OrderId orderId, Quantity quantity) {
        lockAssetCommandHandler.handle(
                LockAssetCommand.builder()
                        .portfolioId(portfolioId)
                        .ticker(ticker)
                        .orderId(orderId)
                        .quantity(quantity)
                        .build());
    }

    @Override
    public void unlockAsset(PortfolioId portfolioId, Ticker ticker, OrderId orderId, Quantity quantity) {
        unlockAssetCommandHandler.handle(
                UnlockAssetCommand.builder()
                        .portfolioId(portfolioId)
                        .ticker(ticker)
                        .orderId(orderId)
                        .quantity(quantity)
                        .build());
    }

    @Override
    public PortfolioDto.PortfolioSummaryJson getPortfolio(PortfolioId portfolioId) {
        GetPortfolioQuery query = GetPortfolioQuery.builder()
                .portfolioId(portfolioId)
                .build();

        Portfolio portfolio = getPortfolioQueryHandler.query(query);
        return portfolioSummaryMapper.map(portfolio, Currency.of("USD"));
    }

    @Override
    public PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(UserId userId) {
        GetAggregatedPortfolioQuery query = GetAggregatedPortfolioQuery.builder()
                .userId(userId)
                .build();
        AggregatedPortfolio aggregatedPortfolio = getAggregatedPortfolioQueryHandler.query(query);
        return portfolioSummaryMapper.map(aggregatedPortfolio, Currency.of("USD"));
    }
}
