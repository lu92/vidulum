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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Slf4j
@Component
@AllArgsConstructor
public class PortfolioSummaryMapper {

    private final QuoteRestClient quoteRestClient;

    public PortfolioDto.PortfolioSummaryJson map(Portfolio portfolio, Currency denominationCurrency) {
        Broker broker = portfolio.getBroker();
        List<PortfolioDto.AssetSummaryJson> denominatedAssets = portfolio.getAssets()
                .stream()
                .map(asset -> mapAssetWithDenomination(portfolio.getBroker(), asset, denominationCurrency))
                .collect(toList());

        Money currentValue = denominatedAssets.stream().reduce(
                Money.zero(denominationCurrency.getId()),
                (accumulatedValue, assetSummary) -> accumulatedValue.plus(assetSummary.getCurrentValue()),
                Money::plus);

        Money denominatedInvestedBalance = denominateInCurrency(portfolio.getInvestedBalance(), broker, denominationCurrency);
        Money profit = currentValue.minus(denominatedInvestedBalance);

        double pctProfit = 0;
        if (!Money.zero(denominationCurrency.getId()).equals(denominatedInvestedBalance)) {
            pctProfit = profit.diffPct(denominatedInvestedBalance);
        }

        return PortfolioDto.PortfolioSummaryJson.builder()
                .portfolioId(portfolio.getPortfolioId().getId())
                .userId(portfolio.getUserId().getId())
                .name(portfolio.getName())
                .broker(portfolio.getBroker().getId())
                .assets(denominatedAssets)
                .status(portfolio.getStatus())
                .investedBalance(denominatedInvestedBalance)
                .currentValue(currentValue)
                .profit(profit)
                .pctProfit(pctProfit)
                .build();
    }

    public PortfolioDto.AggregatedPortfolioSummaryJson map(AggregatedPortfolio aggregatedPortfolio, Currency denominationCurrency) {
        Map<Segment, Map<Broker, List<Asset>>> segmentedAssets = aggregatedPortfolio.fetchSegmentedAssets();
        Set<Segment> segments = segmentedAssets.keySet();
        Map<String, List<PortfolioDto.AssetSummaryJson>> mappedAssets = segments.stream()
                .collect(toMap(Segment::getName, segment -> {
                    Map<Broker, List<Asset>> domainAssets = segmentedAssets.get(segment);
                    return domainAssets.entrySet().stream()
                            .map(entry -> {
                                Broker broker = entry.getKey();
                                List<Asset> assets = entry.getValue();
                                return mapAssets(broker, assets, denominationCurrency);
                            })
                            .flatMap(Collection::stream)
                            .collect(toList());
                }));

        Money currentValue = mappedAssets.values().stream().flatMap(Collection::stream)
                .map(PortfolioDto.AssetSummaryJson::getCurrentValue)
                .reduce(Money.zero(denominationCurrency.getId()), Money::plus);

        List<String> portfolioIds = aggregatedPortfolio.getPortfolioIds().stream()
                .map(PortfolioId::getId)
                .collect(toList());

        Money investedBalanceInDenominatedCurrency = aggregatedPortfolio.getPortfolioInvestedBalances().stream()
                .map(investedBalance -> denominateInCurrency(investedBalance.investedMoney(), investedBalance.broker(), denominationCurrency))
                .reduce(Money.zero(denominationCurrency.getId()), Money::plus);

        Money profit = currentValue.minus(investedBalanceInDenominatedCurrency);

        double pctProfit = Money.zero(denominationCurrency.getId()).equals(investedBalanceInDenominatedCurrency) ?
                0 :
                currentValue.diffPct(investedBalanceInDenominatedCurrency);

        return PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(aggregatedPortfolio.getUserId().getId())
                .segmentedAssets(mappedAssets)
                .portfolioIds(portfolioIds)
                .investedBalance(investedBalanceInDenominatedCurrency.withScale(4))
                .currentValue(currentValue.withScale(4))
                .totalProfit(profit.withScale(4))
                .pctProfit(pctProfit)
                .build();
    }

    private Money denominateInCurrency(Money money, Broker broker, Currency currency) {
        Symbol currencySymbol = Symbol.of(Ticker.of(money.getCurrency()), Ticker.of(currency.getId()));
        log.info("Getting price metadata of [{}]", currencySymbol);
        Price currencyPrice = quoteRestClient.fetch(broker, currencySymbol).getCurrentPrice();
        BigDecimal updatedAmount = money.multiply(currencyPrice.getAmount().doubleValue()).getAmount();
        return Money.of(updatedAmount, currency.getId());
    }

    private List<PortfolioDto.AssetSummaryJson> mapAssets(Broker broker, List<Asset> assets, Currency denominationCurrency) {
        return assets.stream().map(asset -> mapAssetWithDenomination(broker, asset, denominationCurrency)).collect(toList());
    }

    private PortfolioDto.AssetSummaryJson mapAssetWithDenomination(Broker broker, Asset asset, Currency denominatedCurrency) {
        Symbol symbol = Symbol.of(asset.getTicker(), Ticker.of(denominatedCurrency.getId()));
        log.info("Getting price metadata of [{}]", symbol);
        AssetPriceMetadata assetPriceMetadata = quoteRestClient.fetch(broker, symbol);
        Money oldValue = denominateInCurrency(asset.getAvgPurchasePrice().multiply(asset.getQuantity()), broker, denominatedCurrency);
        Money currentValue = assetPriceMetadata.getCurrentPrice().multiply(asset.getQuantity());
        Money profit = currentValue.minus(oldValue);
        double pctProfit = currentValue.diffPct(oldValue);
        log.info("Getting info about asset [{}]", asset.getTicker());
        AssetBasicInfo assetBasicInfo = quoteRestClient.fetchBasicInfoAboutAsset(broker, asset.getTicker());

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
