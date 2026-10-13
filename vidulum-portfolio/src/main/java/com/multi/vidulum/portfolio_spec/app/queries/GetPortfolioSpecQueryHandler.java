package com.multi.vidulum.portfolio_spec.app.queries;

import com.multi.vidulum.portfolio_spec.domain.DomainPortfolioSpecRepository;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetPortfolioSpecQueryHandler
        implements QueryHandler<GetPortfolioSpecQuery, PortfolioSpec> {

    private final DomainPortfolioSpecRepository repository;

    @Override
    public PortfolioSpec query(GetPortfolioSpecQuery query) {
        return repository.findOwnedOrThrow(query.userId(), query.specId());
    }
}
