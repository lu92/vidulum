package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.portfolio.app.AggregatedPortfolio;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class GetAggregatedPortfolioQueryHandler implements QueryHandler<GetAggregatedPortfolioQuery, AggregatedPortfolio> {

    private final DomainPortfolioRepository repository;

    @Override
    public AggregatedPortfolio query(GetAggregatedPortfolioQuery query) {
        List<Portfolio> portfolios = repository.findByUserId(query.getUserId());

        if (portfolios.isEmpty()) {
            throw new RuntimeException("");
        }

        return portfolios
                .stream()
                .reduce(
                        new AggregatedPortfolio(),
                        (aggregatedPortfolio, portfolio) -> {
                            return aggregatedPortfolio;
                        },
                        (aggregatedPortfolio, aggregatedPortfolio2) -> {
                            return aggregatedPortfolio2;
                        });
    }
}
