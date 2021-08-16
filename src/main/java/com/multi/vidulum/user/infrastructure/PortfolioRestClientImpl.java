package com.multi.vidulum.user.infrastructure;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.AggregatedPortfolio;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommand;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommandHandler;
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
    private final GetAggregatedPortfolioQueryHandler getAggregatedPortfolioQueryHandler;
    private final GetPortfolioQueryHandler getPortfolioQueryHandler;
    private final PortfolioSummaryMapper portfolioSummaryMapper;

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
    public PortfolioDto.PortfolioSummaryJson getPortfolio(PortfolioId portfolioId) {
        GetPortfolioQuery query = GetPortfolioQuery.builder()
                .portfolioId(portfolioId)
                .build();

        Portfolio portfolio = getPortfolioQueryHandler.query(query);
        return portfolioSummaryMapper.map(portfolio);
    }

    @Override
    public PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(UserId userId) {
        GetAggregatedPortfolioQuery query = GetAggregatedPortfolioQuery.builder()
                .userId(userId)
                .build();
        AggregatedPortfolio aggregatedPortfolio = getAggregatedPortfolioQueryHandler.query(query);
        return portfolioSummaryMapper.map(aggregatedPortfolio);
    }
}
