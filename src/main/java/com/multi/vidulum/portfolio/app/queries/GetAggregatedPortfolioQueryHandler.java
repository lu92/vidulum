package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Segment;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.app.AggregatedPortfolio;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
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
import static java.util.stream.Collectors.toMap;

@Component
@AllArgsConstructor
public class GetAggregatedPortfolioQueryHandler implements QueryHandler<GetAggregatedPortfolioQuery, AggregatedPortfolio> {

    private final DomainPortfolioRepository repository;
    private final QuoteRestClient quoteRestClient;

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
                                .investedBalance(Money.zero("USD"))
                                .build(),
                        (aggregatedPortfolio, portfolio) -> {

                            // TODO: improve aggregation per all portfolios
                            Map<Ticker, List<AssetBasicInfo>> assetInfoMap = portfolio.getAssets().stream()
                                    .map(asset -> quoteRestClient.fetchBasicInfoAboutAsset(portfolio.getBroker(), asset.getTicker()))
                                    .collect(groupingBy(AssetBasicInfo::getTicker));
                            Map<Ticker, Segment> segmentMap = assetInfoMap.entrySet().stream().collect(toMap(Map.Entry::getKey, tickerListEntry -> tickerListEntry.getValue().get(0).getSegment()));

                            // split assets per segment
                            Map<Segment, List<Asset>> segmentedAssets = portfolio.getAssets().stream()
                                    .collect(groupingBy(asset -> segmentMap.get(asset.getTicker())));

                            // append assets to aggregated-portfolio by segment
                            segmentedAssets.forEach((segment, assets) -> {
                                aggregatedPortfolio.addAssets(segment, portfolio.getBroker(), assets);
                            });

                            // increase number of invested money
                            aggregatedPortfolio.appendInvestedMoney(portfolio.getInvestedBalance());
                            aggregatedPortfolio.appendPortfolioId(portfolio.getPortfolioId());

                            return aggregatedPortfolio;
                        },
                        (firstAggregatedPortfolio, secondAggregatedPortfolio) -> {

                            // combine aggregated-portfolios
                            return secondAggregatedPortfolio;
                        });
    }
}
