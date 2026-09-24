package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.AggregatedPortfolio;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.Contribution;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionStatus;
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

        Ledger ledger = ledgerOf(
                denominated(portfolio.getContributions(), broker, denominationCurrency),
                denominationCurrency);
        Result result = resultOf(mapped, denominationCurrency);

        return PortfolioDto.PortfolioSummaryJson.builder()
                .portfolioId(portfolio.getPortfolioId().getId())
                .userId(portfolio.getUserId().getId())
                .name(portfolio.getName())
                .broker(portfolio.getBroker().getId())
                .assets(mapped.stream().map(MappedAsset::json).collect(toList()))
                .status(portfolio.getStatus())
                .netContributions(ledger.net())
                .contributionCoverage(ledger.coverageShare())
                .contributionStatus(ledger.status())
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

    /**
     * What the owner has put in, net of what they have taken out (task C9).
     *
     * <p>Replaces the single {@code investedBalance} this used to read off the aggregate, which
     * only {@code deposit} and {@code withdraw} ever moved — so a portfolio built from an exchange
     * snapshot answered {@code 0} beside six figures of holdings. Zero is worse than silence: an
     * interface renders it as a number.
     *
     * <p>Withheld below {@link ProfitCoverage#MEANINGFUL_FROM} for the same reason a profit is: a
     * total added up from a minority of the ledger does not describe the ledger. Nothing produces
     * a valueless contribution yet — backfill (C13) will be the first — but the rule is here so
     * that when it does, the number does not quietly start lying.
     */
    /**
     * Restates every valued entry in the currency being asked for.
     *
     * <p>A contribution is recorded in the currency it arrived in, and the summary can be
     * requested in another one — {@code GET /portfolio/{id}/{currency}}. Skipping this made a
     * 10 000 USD deposit answer "10 000 EUR", and made an aggregate add zloty to euro.
     *
     * <p>Entries with no value pass through untouched: there is nothing to convert, and they still
     * have to be counted so coverage can say how much of the ledger is missing.
     */
    private List<Contribution> denominated(
            List<Contribution> contributions, Broker broker, Currency denominationCurrency) {

        if (contributions == null) {
            return List.of();
        }
        return contributions.stream()
                .map(contribution -> contribution.hasKnownValue()
                        ? new Contribution(
                                contribution.id(), contribution.when(), contribution.direction(),
                                contribution.what(),
                                denominateInCurrency(contribution.valueAtArrival(), broker, denominationCurrency),
                                contribution.provenance())
                        : contribution)
                .toList();
    }

    private Ledger ledgerOf(List<Contribution> contributions, Currency denominationCurrency) {
        if (contributions == null || contributions.isEmpty()) {
            return Ledger.absent(ContributionStatus.NOTHING_CONTRIBUTED, null);
        }
        ProfitCoverage coverage = ProfitCoverage.ofLedger(contributions).orElseThrow();

        List<Contribution> valued = contributions.stream().filter(Contribution::hasKnownValue).toList();
        if (valued.isEmpty()) {
            return Ledger.absent(ContributionStatus.NO_KNOWN_VALUE, coverage.share());
        }
        if (!coverage.isMeaningful()) {
            return Ledger.absent(ContributionStatus.WITHHELD_LOW_COVERAGE, coverage.share());
        }

        // Every value is already in the portfolio's own currency - a deposit must be in the
        // currency the portfolio accepts, and an opening contribution is valued when it is made.
        Money net = valued.stream()
                .map(Contribution::signedValue)
                .reduce(Money.zero(denominationCurrency.getId()), Money::plus);
        return new Ledger(net.withScale(4), coverage.share(), ContributionStatus.COMPUTED);
    }

    private record Ledger(Money net, Double coverageShare, ContributionStatus status) {
        static Ledger absent(ContributionStatus status, Double coverageShare) {
            return new Ledger(null, coverageShare, status);
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

        // Each portfolio keeps its own currency and its own broker, so every ledger is converted
        // on its own terms before the entries are merged. Summing first and converting after would
        // add zloty to euro and call the result dollars.
        Ledger ledger = ledgerOf(
                aggregatedPortfolio.getPortfolioContributions().stream()
                        .flatMap(entry -> denominated(
                                entry.contributions(), entry.broker(), denominationCurrency).stream())
                        .toList(),
                denominationCurrency);

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
                .netContributions(ledger.net())
                .contributionCoverage(ledger.coverageShare())
                .contributionStatus(ledger.status())
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
