package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.AggregatedPortfolio;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Component
@AllArgsConstructor
public class PortfolioSummaryMapper {

    private final QuoteRestClient quoteRestClient;

    public PortfolioDto.PortfolioSummaryJson map(Portfolio portfolio) {
        List<PortfolioDto.AssetSummaryJson> assets = portfolio.getAssets()
                .stream()
                .map(asset -> mapAsset(portfolio.getBroker(), asset))
                .collect(toList());


        Money currentValue = assets.stream().reduce(
                Money.zero("USD"),
                (accumulatedValue, assetSummary) -> accumulatedValue.plus(assetSummary.getCurrentValue()),
                Money::plus);

        Money profit = currentValue.minus(portfolio.getInvestedBalance());


        double pctProfit = 0;
        if (!Money.zero("USD").equals(portfolio.getInvestedBalance())) {
            pctProfit = profit.diffPct(portfolio.getInvestedBalance());
        }

        return PortfolioDto.PortfolioSummaryJson.builder()
                .portfolioId(portfolio.getPortfolioId().getId())
                .userId(portfolio.getUserId().getId())
                .name(portfolio.getName())
                .broker(portfolio.getBroker().getId())
                .assets(assets)
                .status(portfolio.getStatus())
                .investedBalance(portfolio.getInvestedBalance())
                .currentValue(currentValue)
                .profit(profit)
                .pctProfit(pctProfit)
                .build();
    }

    public PortfolioDto.AggregatedPortfolioSummaryJson map(AggregatedPortfolio aggregatedPortfolio) {
        Map<Segment, Map<Broker, List<Asset>>> segmentedAssets = aggregatedPortfolio.fetchSegmentedAssets();
        Set<Segment> segments = segmentedAssets.keySet();
        Map<String, List<PortfolioDto.AssetSummaryJson>> mappedAssets = segments.stream()
                .collect(toMap(Segment::getName, segment -> {
                    Map<Broker, List<Asset>> domainAssets = segmentedAssets.get(segment);
                    return domainAssets.entrySet().stream()
                            .map(entry -> {
                                Broker broker = entry.getKey();
                                List<Asset> assets = entry.getValue();
                                return mapAssets(broker, assets);
                            })
                            .flatMap(Collection::stream)
                            .collect(toList());
                }));

        Money currentValue = mappedAssets.values().stream().flatMap(Collection::stream)
                .map(PortfolioDto.AssetSummaryJson::getCurrentValue)
                .reduce(Money.zero("USD"), Money::plus);

        List<String> portfolioIds = aggregatedPortfolio.getPortfolioIds().stream()
                .map(PortfolioId::getId)
                .collect(toList());

        Money profit = currentValue.minus(aggregatedPortfolio.getInvestedBalance());

        double pctProfit = Money.zero("USD").equals(aggregatedPortfolio.getInvestedBalance()) ?
                0 :
                currentValue.diffPct(aggregatedPortfolio.getInvestedBalance());

        return PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(aggregatedPortfolio.getUserId().getId())
                .segmentedAssets(mappedAssets)
                .portfolioIds(portfolioIds)
                .investedBalance(aggregatedPortfolio.getInvestedBalance().withScale(4))
                .currentValue(currentValue.withScale(4))
                .totalProfit(profit.withScale(4))
                .pctProfit(pctProfit)
                .build();
    }


    private List<PortfolioDto.AssetSummaryJson> mapAssets(Broker broker, List<Asset> assets) {
        return assets.stream().map(asset -> mapAsset(broker, asset)).collect(toList());
    }

    private PortfolioDto.AssetSummaryJson mapAsset(Broker broker, Asset asset) {
        AssetPriceMetadata assetPriceMetadata = quoteRestClient.fetch(broker, Symbol.of(asset.getTicker(), Ticker.of("USD")));
        AssetBasicInfo assetBasicInfo = quoteRestClient.fetchBasicInfoAboutAsset(broker, asset.getTicker());
        Money oldValue = asset.getAvgPurchasePrice().multiply(asset.getQuantity());
        Money currentValue = assetPriceMetadata.getCurrentPrice().multiply(asset.getQuantity());
        Money profit = currentValue.minus(oldValue);
        double pctProfit = currentValue.diffPct(oldValue);

        return PortfolioDto.AssetSummaryJson.builder()
                .ticker(asset.getTicker().getId())
                .fullName(assetBasicInfo.getFullName())
                .avgPurchasePrice(asset.getAvgPurchasePrice().withScale(4))
                .quantity(asset.getQuantity())
                .locked(asset.getLocked())
                .free(asset.getFree())
                .tags(assetBasicInfo.getTags())
                .pctProfit(pctProfit)
                .profit(profit.withScale(4))
                .currentPrice(assetPriceMetadata.getCurrentPrice().withScale(4))
                .currentValue(currentValue.withScale(4))
                .build();
    }
}
