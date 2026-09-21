package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.AggregatedPortfolio;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitCoverage;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitStatus;
import com.multi.vidulum.common.PortfolioId;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Slf4j
@Component
@AllArgsConstructor
public class PortfolioSummaryMapper {

    private final QuoteRestClient quoteRestClient;

    public PortfolioDto.PortfolioSummaryJson map(Portfolio portfolio, Currency denominationCurrency) {
        Broker broker = portfolio.getBroker();
        List<MappedAsset> mapped = portfolio.getAssets()
                .stream()
                .map(asset -> mapAssetWithDenomination(broker, asset, denominationCurrency))
                .collect(toList());

        Money currentValue = mapped.stream()
                .map(m -> m.json().getCurrentValue())
                .reduce(Money.zero(denominationCurrency.getId()), Money::plus);

        Money denominatedInvestedBalance =
                denominateInCurrency(portfolio.getInvestedBalance(), broker, denominationCurrency);
        Result result = resultOf(mapped, denominationCurrency);

        return PortfolioDto.PortfolioSummaryJson.builder()
                .portfolioId(portfolio.getPortfolioId().getId())
                .userId(portfolio.getUserId().getId())
                .name(portfolio.getName())
                .broker(portfolio.getBroker().getId())
                .assets(mapped.stream().map(MappedAsset::json).collect(toList()))
                .status(portfolio.getStatus())
                .investedBalance(denominatedInvestedBalance)
                .currentValue(currentValue)
                .unrealisedProfit(result.profit())
                .pctUnrealisedProfit(result.pctProfit())
                .profitCoverage(result.coverageShare())
                .profitStatus(result.status())
                .build();
    }

    /**
     * The portfolio's result, built from what positions cost rather than from
     * {@code investedBalance} (task C3).
     *
     * <p>The old formula was {@code currentValue - investedBalance}, and only {@code deposit} and
     * {@code withdraw} ever move {@code investedBalance}. A portfolio created from an exchange
     * snapshot goes through neither, so it reported its <b>entire value</b> as profit — 147 000
     * EUR made out of nothing. That is the same defect C3 fixed per position, surviving one level
     * up, and no amount of care at the asset level could correct a total computed from a
     * different quantity.
     *
     * <p>Summing the positions instead means the total inherits their honesty: positions whose
     * cost nobody knows contribute nothing, and what remains is measured against what it actually
     * cost. {@code investedBalance} is still reported — task C9 decides what it should mean — but
     * nothing is derived from it any more.
     */
    private Result resultOf(List<MappedAsset> mapped, Currency denominationCurrency) {
        String currency = denominationCurrency.getId();
        if (mapped.isEmpty()) {
            return Result.absent(ProfitStatus.NOTHING_HELD, null);
        }

        List<ProfitCoverage.Weight> weights = new ArrayList<>(mapped.size());
        for (MappedAsset asset : mapped) {
            weights.add(new ProfitCoverage.Weight(
                    asset.json().getCurrentValue().getAmount().doubleValue(),
                    asset.coverage() != null ? asset.coverage().share() : 0));
        }
        Optional<ProfitCoverage> coverage = ProfitCoverage.weighted(weights);
        if (coverage.isEmpty()) {
            return Result.absent(ProfitStatus.NOTHING_HELD, null);
        }

        List<MappedAsset> priced = mapped.stream().filter(MappedAsset::hasKnownCost).collect(toList());
        if (priced.isEmpty()) {
            return Result.absent(ProfitStatus.NO_KNOWN_COST, coverage.get().share());
        }
        if (!coverage.get().isMeaningful()) {
            return Result.absent(ProfitStatus.WITHHELD_LOW_COVERAGE, coverage.get().share());
        }

        Money coveredValue = priced.stream().map(MappedAsset::coveredValue)
                .reduce(Money.zero(currency), Money::plus);
        Money knownCost = priced.stream().map(MappedAsset::knownCost)
                .reduce(Money.zero(currency), Money::plus);

        return new Result(
                coveredValue.minus(knownCost).withScale(4),
                coveredValue.diffPct(knownCost),
                coverage.get().share(),
                ProfitStatus.COMPUTED);
    }

    /**
     * A position after denomination, plus the two figures the portfolio total needs and the JSON
     * does not carry precisely enough to recover — {@code profit} is rounded for display, and
     * deriving the cost back out of it would drift.
     */
    private record MappedAsset(
            PortfolioDto.AssetSummaryJson json,
            ProfitCoverage coverage,
            Money coveredValue,
            Money knownCost) {

        boolean hasKnownCost() {
            return knownCost != null;
        }
    }

    private record Result(Money profit, Double pctProfit, Double coverageShare, ProfitStatus status) {
        static Result absent(ProfitStatus status, Double coverageShare) {
            return new Result(null, null, coverageShare, status);
        }
    }

    public PortfolioDto.AggregatedPortfolioSummaryJson map(AggregatedPortfolio aggregatedPortfolio, Currency denominationCurrency) {
        Map<Segment, Map<Broker, List<Asset>>> segmentedAssets = aggregatedPortfolio.fetchSegmentedAssets();
        Set<Segment> segments = segmentedAssets.keySet();
        Map<String, List<MappedAsset>> mappedAssets = segments.stream()
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
                .map(m -> m.json().getCurrentValue())
                .reduce(Money.zero(denominationCurrency.getId()), Money::plus);

        List<String> portfolioIds = aggregatedPortfolio.getPortfolioIds().stream()
                .map(PortfolioId::getId)
                .collect(toList());

        Money investedBalanceInDenominatedCurrency = aggregatedPortfolio.getPortfolioInvestedBalances().stream()
                .map(investedBalance -> denominateInCurrency(investedBalance.investedMoney(), investedBalance.broker(), denominationCurrency))
                .reduce(Money.zero(denominationCurrency.getId()), Money::plus);

        // Same rule as one portfolio: the total is the positions' result, not the gap between
        // value and deposits. The aggregated view merges by ticker alone, which dilutes averages
        // across sources - that is C6, and this change neither causes nor cures it.
        Result result = resultOf(
                mappedAssets.values().stream().flatMap(Collection::stream).collect(toList()),
                denominationCurrency);

        return PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                .userId(aggregatedPortfolio.getUserId().getId())
                .segmentedAssets(mappedAssets.entrySet().stream().collect(toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(MappedAsset::json).collect(toList()))))
                .portfolioIds(portfolioIds)
                .investedBalance(investedBalanceInDenominatedCurrency.withScale(4))
                .currentValue(currentValue.withScale(4))
                .totalUnrealisedProfit(result.profit())
                .pctUnrealisedProfit(result.pctProfit())
                .profitCoverage(result.coverageShare())
                .profitStatus(result.status())
                .build();
    }

    private Money denominateInCurrency(Money money, Broker broker, Currency currency) {
        Symbol currencySymbol = Symbol.of(Ticker.of(money.getCurrency()), Ticker.of(currency.getId()));
        log.info("Getting price metadata of [{}]", currencySymbol);
        Price currencyPrice = quoteRestClient.fetch(broker, currencySymbol).getCurrentPrice();
        BigDecimal updatedAmount = money.multiply(currencyPrice.getAmount().doubleValue()).getAmount();
        return Money.of(updatedAmount, currency.getId());
    }

    private List<MappedAsset> mapAssets(Broker broker, List<Asset> assets, Currency denominationCurrency) {
        return assets.stream().map(asset -> mapAssetWithDenomination(broker, asset, denominationCurrency)).collect(toList());
    }

    private MappedAsset mapAssetWithDenomination(Broker broker, Asset asset, Currency denominatedCurrency) {
        Symbol symbol = Symbol.of(asset.getTicker(), Ticker.of(denominatedCurrency.getId()));
        log.info("Getting price metadata of [{}]", symbol);
        AssetPriceMetadata assetPriceMetadata = quoteRestClient.fetch(broker, symbol);
        Money currentValue = assetPriceMetadata.getCurrentPrice().multiply(asset.getQuantity());

        // Profit is computed only over the part whose cost we know, and only if we know any.
        // Both sides of the subtraction refer to the same units: the old code multiplied the
        // known part's average price by the WHOLE balance, so a position with 0.3 bought out of
        // 100 held reported an invented profit. When nothing is known, the figures stay absent
        // rather than defaulting to zero - reporting zero would present a guess as a fact.
        Money profit = null;
        Double pctProfit = null;
        Money knownCost = null;
        Money coveredValue = null;
        if (asset.hasKnownCost()) {
            knownCost = denominateInCurrency(
                    asset.knownCost().orElseThrow(), broker, denominatedCurrency);
            coveredValue = assetPriceMetadata.getCurrentPrice().multiply(asset.coveredQuantity());
            profit = coveredValue.minus(knownCost);
            pctProfit = coveredValue.diffPct(knownCost);
        }
        ProfitCoverage coverage = ProfitCoverage.ofPosition(asset).orElse(null);
        log.info("Getting info about asset [{}]", asset.getTicker());
        AssetBasicInfo assetBasicInfo = quoteRestClient.fetchBasicInfoAboutAsset(broker, asset.getTicker());

        Set<PortfolioDto.AssetLockJson> activeLocks = asset.getActiveLocks().stream()
                .map(lock -> PortfolioDto.AssetLockJson.builder()
                        .orderId(lock.orderId().getId())
                        .quantity(lock.locked())
                        .build())
                .collect(Collectors.toSet());

        PortfolioDto.AssetSummaryJson json = PortfolioDto.AssetSummaryJson.builder()
                .ticker(asset.getTicker().getId())
                .fullName(assetBasicInfo.getFullName())
                .costBasis(PortfolioDto.CostBasisJson.from(asset.getCostBasis()))
                .quantity(asset.getQuantity())
                .locked(asset.getLocked())
                .free(asset.getFree())
                .activeLocks(activeLocks)
                .tags(assetBasicInfo.getTags())
                .pctUnrealisedProfit(pctProfit)
                .unrealisedProfit(profit != null ? profit.withScale(4) : null)
                .currentPrice(assetPriceMetadata.getCurrentPrice().withScale(4))
                .currentValue(currentValue.withScale(4))
                .coverage(coverage != null ? coverage.share() : null)
                .build();

        return new MappedAsset(json, coverage, coveredValue, knownCost);
    }
}
