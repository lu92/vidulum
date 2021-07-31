package com.multi.vidulum.user.infrastructure;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommand;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommandHandler;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.user.domain.PortfolioRestClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class PortfolioRestClientImpl implements PortfolioRestClient {

    private final CreateEmptyPortfolioCommandHandler createEmptyPortfolioCommandHandler;

    @Override
    public PortfolioId createPortfolio(String name, UserId userId, Broker broker) {
        CreateEmptyPortfolioCommand command = CreateEmptyPortfolioCommand.builder()
                .name(name)
                .userId(userId)
                .broker(broker)
                .build();
        Portfolio portfolio = createEmptyPortfolioCommandHandler.handle(command);
        return portfolio.getPortfolioId();
    }

    @Override
    public PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(UserId portfolioId) {
        return null;
    }
}
