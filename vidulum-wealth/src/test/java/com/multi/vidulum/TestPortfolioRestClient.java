package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommand;
import com.multi.vidulum.portfolio.app.commands.lock.LockAssetCommand;
import com.multi.vidulum.portfolio.app.commands.unlock.UnlockAssetCommand;
import com.multi.vidulum.portfolio.app.PortfolioRestController;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioRestClient;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Test implementation of PortfolioRestClient using CQRS gateways directly.
 * Replaces PortfolioRestClientImpl from vidulum-app which depends on user domain.
 */
@Component
public class TestPortfolioRestClient implements PortfolioRestClient {

    private final CommandGateway commandGateway;
    private final PortfolioRestController portfolioRestController;

    public TestPortfolioRestClient(@Lazy CommandGateway commandGateway, @Lazy PortfolioRestController portfolioRestController) {
        this.commandGateway = commandGateway;
        this.portfolioRestController = portfolioRestController;
    }

    @Override
    public PortfolioId createPortfolio(String name, UserId userId, Broker broker, Currency allowedDepositCurrency) {
        Portfolio portfolio = commandGateway.send(
                CreateEmptyPortfolioCommand.builder()
                        .portfolioId(PortfolioId.generate())
                        .name(name)
                        .userId(userId)
                        .broker(broker)
                        .allowedDepositCurrency(allowedDepositCurrency)
                        .build());
        return portfolio.getPortfolioId();
    }

    @Override
    public void lockAsset(PortfolioId portfolioId, Ticker ticker, OrderId orderId, Quantity quantity) {
        commandGateway.send(LockAssetCommand.builder()
                .portfolioId(portfolioId)
                .ticker(ticker)
                .orderId(orderId)
                .quantity(quantity)
                .build());
    }

    @Override
    public void unlockAsset(PortfolioId portfolioId, Ticker ticker, OrderId orderId, Quantity quantity) {
        commandGateway.send(UnlockAssetCommand.builder()
                .portfolioId(portfolioId)
                .ticker(ticker)
                .orderId(orderId)
                .quantity(quantity)
                .build());
    }

    @Override
    public PortfolioDto.PortfolioSummaryJson getPortfolio(PortfolioId portfolioId) {
        return portfolioRestController.getPortfolio(portfolioId.getId(), "USD");
    }

    @Override
    public PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(UserId userId) {
        return portfolioRestController.getAggregatedPortfolio(userId.getId(), "USD");
    }
}
