package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.portfolio.app.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.app.model.PortfolioSummary;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetPortfolioQueryHandler implements QueryHandler<GetPortfolioQuery, PortfolioSummary> {

    private final DomainPortfolioRepository repository;
    private final PortfolioSummaryMapper portfolioSummaryMapper;

    @Override
    public PortfolioSummary query(GetPortfolioQuery query) {
        return repository.findById(query.getPortfolioId())
                .map(portfolioSummaryMapper::mapAsset)
                .orElseThrow(() -> new PortfolioNotFoundException(query.getPortfolioId()));
    }
}
