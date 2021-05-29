package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Segment;
import com.multi.vidulum.portfolio.app.AggregatedPortfolio;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;

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
                        AggregatedPortfolio.builder()
                                .userId(query.getUserId())
                                .segmentedAssets(new HashMap<>())
                                .xxx(new HashMap<>())
                                .investedBalance(Money.zero("USD"))
                                .build(),
                        (aggregatedPortfolio, portfolio) -> {

                            // split assets per segment
                            Map<Segment, List<Asset>> segmentedAssets = portfolio.getAssets().stream()
                                    .collect(groupingBy(Asset::getSegment));

                            // append assets to aggregated-portfolio by segment
                            segmentedAssets.forEach((segment, assets) -> {
                                aggregatedPortfolio.addAssets(segment, portfolio.getBroker(), assets);
                            });

                            // increase number of invested money
                            aggregatedPortfolio.appendInvestedMoney(portfolio.getInvestedBalance());

                            return aggregatedPortfolio;
                        },
                        (firstAggregatedPortfolio, secondAggregatedPortfolio) -> {

                            // combine aggregated-portfolios
                            return secondAggregatedPortfolio;
                        });
    }
}
