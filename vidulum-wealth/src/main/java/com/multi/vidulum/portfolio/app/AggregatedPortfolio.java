package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.common.PortfolioId;
import lombok.*;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregatedPortfolio {
    private UserId userId;
    private Map<Segment, GroupedAssets> segmentedAssets = new HashMap<>();
    private List<PortfolioId> portfolioIds = new LinkedList<>();
    private List<PortfolioContributions> portfolioContributions;
    private List<PortfolioRealisedResults> portfolioRealisedResults;

    public void addAssets(Segment segment, Broker broker, List<Asset> assets) {

        Map<PositionKey, Asset> mergedAssets = mergeAssetsAtSamePosition(assets);

        segmentedAssets.compute(segment, (foundSegment, groupedAssets) -> {
            if (groupedAssets == null) {
                groupedAssets = new GroupedAssets();
            }
            for (Asset asset : mergedAssets.values()) {
                groupedAssets.appendAsset(broker, asset);
            }

            return groupedAssets;
        });
    }

    public Map<Segment, Map<Broker, List<Asset>>> fetchSegmentedAssets() {
        Map<Segment, Map<Broker, List<Asset>>> outcome = new HashMap<>();

        segmentedAssets.forEach((segment, groupedAssets) -> {
            outcome.put(segment, groupedAssets.portfolio);
        });

        return outcome;
    }

    public void appendPortfolioId(PortfolioId portfolioId) {
        if (portfolioIds == null) {
            portfolioIds = new LinkedList<>();
        }
        portfolioIds.add(portfolioId);
    }

    public void appendPortfolioContributions(PortfolioContributions contributions) {
        if (portfolioContributions == null) {
            portfolioContributions = new LinkedList<>();
        }
        portfolioContributions.add(contributions);
    }

    public void appendPortfolioRealisedResults(PortfolioRealisedResults results) {
        if (portfolioRealisedResults == null) {
            portfolioRealisedResults = new LinkedList<>();
        }
        portfolioRealisedResults.add(results);
    }

    /**
     * One portfolio's settled sales (task F6), kept per portfolio for the same reason its ledger
     * is: each was priced at its own broker, and what they add up to depends on the currency being
     * asked for.
     */
    public record PortfolioRealisedResults(
            PortfolioId portfolioId,
            List<com.multi.vidulum.portfolio.domain.portfolio.RealisedResult> results,
            Broker broker) {
    }

    @Value
    @Builder
    public static class GroupedAssets {
        Map<Broker, List<Asset>> portfolio = new HashMap<>();

        public void appendAsset(Broker broker, Asset asset) {
            findRelatedAsset(broker, asset)
                    .ifPresentOrElse(relatedAsset -> {

                        // asset-portfolio is already having asset with same ticker so lets update asset's amount

                        Quantity quantity = relatedAsset.getQuantity().plus(asset.getQuantity());
                        Quantity lockedQuantity = relatedAsset.getLocked().plus(asset.getLocked());
                        Quantity freeQuantity = relatedAsset.getFree().plus(asset.getFree());

                        Asset updatedAsset = Asset.builder()
                                .ticker(relatedAsset.getTicker())
                                .subName(relatedAsset.getSubName())
                                .costBasis(mergeCost(relatedAsset.getCostBasis(), asset.getCostBasis()))
                                .quantity(quantity)
                                .locked(lockedQuantity)
                                .free(freeQuantity)
                                .activeLocks(relatedAsset.getActiveLocks())
                                .build();

                        List<Asset> assets = portfolio.get(broker);
                        assets.remove(relatedAsset);
                        assets.add(updatedAsset);
                        portfolio.put(broker, assets);

                    }, () -> {

                        // there is no asset so lets add new one to asset-portfolio

                        List<Asset> assets = portfolio.getOrDefault(broker, new ArrayList<>());
                        assets.add(asset);
                        portfolio.put(broker, assets);
                    });
        }

        private Optional<Asset> findRelatedAsset(Broker broker, Asset asset) {
            return portfolio.getOrDefault(broker, List.of()).stream()
                    .filter(held -> PositionKey.of(held).equals(PositionKey.of(asset)))
                    .findFirst();
        }
    }

    /**
     * What makes two lines the same position (task C6).
     *
     * <p>The ticker alone is not enough. C2 split a holding by where it came from precisely because
     * one bitcoin bought here and one transferred in from elsewhere are different facts: the first
     * has a price, the second has none. Merging them on ticker rebuilt the very thing C2 took
     * apart — the aggregated view answered with a single BTC line whose average price covered a
     * fraction of the quantity, and the old code even stamped it {@code SubName.none()}, erasing
     * the evidence that anything had been merged.
     */
    private record PositionKey(Ticker ticker, SubName subName) {
        static PositionKey of(Asset asset) {
            return new PositionKey(asset.getTicker(), asset.getSubName());
        }
    }

    private Map<PositionKey, Asset> mergeAssetsAtSamePosition(List<Asset> assets) {
        Map<PositionKey, List<Asset>> grouped = assets.stream()
                .collect(Collectors.groupingBy(PositionKey::of));

        return grouped.entrySet().stream()
                .collect(toMap(
                        Map.Entry::getKey,
                        entry -> {
                            List<Asset> selectedAssets = entry.getValue();
                            Asset firstAsset = selectedAssets.get(0);
                            return selectedAssets.stream()
                                    .reduce(
                                            Asset.builder()
                                                    .ticker(firstAsset.getTicker())
                                                    // The group's own subName, not none(): these
                                                    // lines are the same position, and saying
                                                    // otherwise loses where the holding came from.
                                                    .subName(firstAsset.getSubName())
                                                    .costBasis(null)
                                                    .quantity(Quantity.zero(firstAsset.getQuantity().getUnit()))
                                                    .locked(Quantity.zero(firstAsset.getQuantity().getUnit()))
                                                    .free(Quantity.zero(firstAsset.getQuantity().getUnit()))
                                                    .activeLocks(new HashSet<>())
                                                    .build(),

                                            (identityAsset, nextAsset) -> {

                                                Quantity quantity = identityAsset.getQuantity().plus(nextAsset.getQuantity());
                                                Quantity lockedQuantity = identityAsset.getLocked().plus(nextAsset.getLocked());
                                                Quantity freeQuantity = identityAsset.getFree().plus(nextAsset.getFree());

                                                identityAsset.setCostBasis(
                                                        mergeCost(identityAsset.getCostBasis(), nextAsset.getCostBasis()));
                                                identityAsset.setQuantity(quantity);
                                                identityAsset.setLocked(lockedQuantity);
                                                identityAsset.setFree(freeQuantity);
                                                identityAsset.getActiveLocks().addAll(nextAsset.getActiveLocks());
                                                return identityAsset;
                                            });
                        }));
    }

    /**
     * One portfolio's ledger, kept per portfolio rather than pre-summed (task C9): each carries
     * its own currency and broker, and the aggregate cannot add them up before it knows what to
     * add them up into.
     */
    public record PortfolioContributions(PortfolioId portfolioId,
                                         Currency originCurrency,
                                         List<com.multi.vidulum.portfolio.domain.portfolio.Contribution> contributions,
                                         Broker broker) {
    }

    /**
     * Merges the known parts of two costs and nothing else.
     *
     * <p>A position whose cost is unknown contributes no price to the average — it only enlarges
     * the quantity the average fails to cover. The old code averaged through {@code getValue()},
     * so an unknown cost entered as zero and quietly dragged the average down.
     */
    private static CostBasis mergeCost(CostBasis left, CostBasis right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.merge(right);
    }
}
