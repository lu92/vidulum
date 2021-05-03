package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetPortfolioQueryHandler implements QueryHandler<GetPortfolioQuery, Portfolio> {

    private final DomainPortfolioRepository repository;

    @Override
    public Portfolio query(GetPortfolioQuery query) {
        return repository.findById(query.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(query.getPortfolioId()));
    }
}
