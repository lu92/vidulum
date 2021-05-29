package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private Money investedBalance;

    public void addAssets(Segment segment, Broker broker, List<Asset> assets) {

        Map<Ticker, Asset> mergedAssets = mergeAssetsWithSameTicker(assets);

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

    class GroupedAssets {
        private final Map<Broker, List<Asset>> portfolio = new HashMap<>();

        void appendAsset(Broker broker, Asset asset) {
            findRelatedAsset(broker, asset.getTicker())
                    .ifPresentOrElse(relatedAsset -> {

                        // asset-portfolio is already having asset with same ticker so lets update asset's amount

                        Quantity quantity = relatedAsset.getQuantity().plus(asset.getQuantity());
                        Money summarizedValue = relatedAsset.getValue().plus(asset.getValue());
                        Money avgPurchasePrice = summarizedValue.divide(quantity.getQty());


                        Asset updatedAsset = Asset.builder()
                                .ticker(relatedAsset.getTicker())
                                .fullName(relatedAsset.getFullName())
                                .segment(relatedAsset.getSegment())
                                .subName(relatedAsset.getSubName())
                                .avgPurchasePrice(avgPurchasePrice)
                                .quantity(quantity)
                                .tags(relatedAsset.getTags())
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

        private Optional<Asset> findRelatedAsset(Broker broker, Ticker ticker) {
            return portfolio.getOrDefault(broker, List.of()).stream()
                    .filter(asset -> ticker.equals(asset.getTicker()))
                    .findFirst();
        }
    }

    public void appendInvestedMoney(Money money) {
        investedBalance = investedBalance.plus(money);
    }

    private Map<Ticker, Asset> mergeAssetsWithSameTicker(List<Asset> assets) {
        Map<Ticker, List<Asset>> groupedAssetsPerTicker = assets.stream().collect(Collectors.groupingBy(Asset::getTicker));
        return groupedAssetsPerTicker.entrySet().stream()
                .collect(toMap(
                        Map.Entry::getKey,
                        entry -> {
                            List<Asset> selectedAssets = entry.getValue();
                            Asset firstAsset = selectedAssets.get(0);
                            return selectedAssets.stream()
                                    .reduce(
                                            Asset.builder()
                                                    .ticker(firstAsset.getTicker())
                                                    .fullName(firstAsset.getFullName())
                                                    .segment(firstAsset.getSegment())
                                                    .subName(SubName.none())
                                                    .avgPurchasePrice(Money.zero("USD"))
                                                    .quantity(Quantity.zero(firstAsset.getQuantity().getUnit()))
                                                    .tags(firstAsset.getTags())
                                                    .build(),

                                            (identityAsset, nextAsset) -> {

                                                Quantity quantity = identityAsset.getQuantity().plus(nextAsset.getQuantity());
                                                Money summarizedValue = identityAsset.getValue().plus(nextAsset.getValue());
                                                Money avgPurchasePrice = summarizedValue.divide(quantity.getQty());

                                                identityAsset.setAvgPurchasePrice(avgPurchasePrice);
                                                identityAsset.setQuantity(quantity);
                                                return identityAsset;
                                            });
                        }));
    }
}
