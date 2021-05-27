package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.AssetNotFoundException;
import com.multi.vidulum.portfolio.domain.NotSufficientBalance;
import com.multi.vidulum.portfolio.domain.portfolio.snapshots.PortfolioSnapshot;
import com.multi.vidulum.portfolio.domain.trades.AssetPortion;
import com.multi.vidulum.portfolio.domain.trades.BuyTrade;
import com.multi.vidulum.portfolio.domain.trades.SellTrade;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Data
@Builder
public class Portfolio implements Aggregate<PortfolioId, PortfolioSnapshot> {
    private PortfolioId portfolioId;
    private UserId userId;
    private String name;
    private Broker broker;
    private List<Asset> assets;
    private Money investedBalance;

    @Override
    public PortfolioSnapshot getSnapshot() {
        List<PortfolioSnapshot.AssetSnapshot> assetSnapshots = assets.stream()
                .map(asset -> new PortfolioSnapshot.AssetSnapshot(
                        asset.getTicker(),
                        asset.getFullName(),
                        asset.getSegment(),
                        asset.getSubName(),
                        asset.getAvgPurchasePrice(),
                        asset.getQuantity(),
                        asset.getTags()))
                .collect(Collectors.toList());

        return new PortfolioSnapshot(
                portfolioId,
                userId,
                name,
                broker,
                assetSnapshots,
                investedBalance
        );
    }

    public static Portfolio from(PortfolioSnapshot snapshot) {
        List<Asset> assets = snapshot.getAssets().stream()
                .map(assetSnapshot -> new Asset(
                        assetSnapshot.getTicker(),
                        assetSnapshot.getFullName(),
                        assetSnapshot.getSegment(),
                        assetSnapshot.getSubName(),
                        assetSnapshot.getAvgPurchasePrice(),
                        assetSnapshot.getQuantity(),
                        assetSnapshot.getTags()
                ))
                .collect(Collectors.toList());

        return Portfolio.builder()
                .portfolioId(snapshot.getPortfolioId())
                .userId(snapshot.getUserId())
                .name(snapshot.getName())
                .broker(snapshot.getBroker())
                .assets(assets)
                .investedBalance(snapshot.getInvestedBalance())
                .build();
    }


    public void handleExecutedTrade(BuyTrade trade, AssetBasicInfo assetBasicInfo) {
        AssetPortion soldPortion = trade.clarifySoldPortion();
        AssetPortion purchasedPortion = trade.clarifyPurchasedPortion();
        swing(soldPortion, purchasedPortion, assetBasicInfo);
        System.out.println(this);
    }

    public void handleExecutedTrade(SellTrade trade, AssetBasicInfo assetBasicInfo) {
        AssetPortion soldPortion = trade.clarifySoldPortion();
        AssetPortion purchasedPortion = trade.clarifyPurchasedPortion();
        swing(soldPortion, purchasedPortion, assetBasicInfo);
    }

    private void swing(AssetPortion soldPortion, AssetPortion purchasedPortion, AssetBasicInfo purchasedAssetBasicInfo) {
        reduceAsset(soldPortion);
        increaseAsset(purchasedPortion, purchasedAssetBasicInfo);
    }

    private void increaseAsset(AssetPortion purchasedPortion, AssetBasicInfo assetBasicInfo) {
        findAssetByTickerAndSubName(purchasedPortion.getTicker(), purchasedPortion.getSubName())
                .ifPresentOrElse(existingAsset -> {
                    Quantity totalQuantity = existingAsset.getQuantity().plus(purchasedPortion.getQuantity());
                    Money totalValue = existingAsset.getValue().plus(purchasedPortion.getValue());
                    Money updatedAvgPurchasePrice = totalValue.divide(totalQuantity.getQty());

                    existingAsset.setQuantity(totalQuantity);
                    existingAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
                }, () -> {
                    Asset newAsset = Asset.builder()
                            .ticker(assetBasicInfo.getTicker())
                            .fullName(assetBasicInfo.getFullName())
                            .subName(purchasedPortion.getSubName())
                            .segment(assetBasicInfo.getSegment())
                            .avgPurchasePrice(purchasedPortion.getPrice())
                            .quantity(purchasedPortion.getQuantity())
                            .tags(assetBasicInfo.getTags())
                            .build();
                    assets.add(newAsset);
                });
    }

    private void reduceAsset(AssetPortion soldPortion) {
        Asset soldAsset = findAssetByTickerAndSubName(soldPortion.getTicker(), soldPortion.getSubName())
                .orElseThrow(() -> new AssetNotFoundException(soldPortion.getTicker()));

        if (soldPortion.getQuantity().getQty() > soldAsset.getQuantity().getQty()) {
            throw new NotSufficientBalance(soldPortion.getValue());
        }

        Quantity decreasedQuantity = soldAsset.getQuantity().minus(soldPortion.getQuantity());
        boolean isAssetSoldOutFully = soldAsset.getQuantity().equals(soldPortion.getQuantity());
        if (isAssetSoldOutFully) {
            assets.remove(soldAsset);
        } else {
            Money totalValue = soldAsset.getValue().minus(soldPortion.getValue());
            Money updatedAvgPurchasePrice = totalValue.divide(decreasedQuantity.getQty());

            soldAsset.setQuantity(decreasedQuantity);
            soldAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
        }
    }

    public void depositMoney(Money deposit) {
        Ticker ticker = Ticker.of(deposit.getCurrency());
        findAssetByTicker(ticker).ifPresentOrElse(existingAsset -> {
            Quantity updatedQuantity = Quantity.of(existingAsset.getQuantity().getQty() + deposit.getAmount().doubleValue());
            existingAsset.setQuantity(updatedQuantity);
        }, () -> {
            Asset cash = Asset.builder()
                    .ticker(ticker)
                    .fullName("")
                    .subName(SubName.none())
                    .avgPurchasePrice(Money.one(deposit.getCurrency()))
                    .quantity(Quantity.of(deposit.getAmount().doubleValue()))
                    .tags(List.of("currency", deposit.getCurrency()))
                    .build();
            assets.add(cash);
        });
        investedBalance = investedBalance.plus(deposit);
    }

    public void withdrawMoney(Money withdrawal) {
        Ticker ticker = Ticker.of(withdrawal.getCurrency());
        Asset cash = findAssetByTicker(ticker)
                .orElseThrow(() -> new AssetNotFoundException(ticker));

        if (cash.getQuantity().getQty() < withdrawal.getAmount().doubleValue()) {
            throw new NotSufficientBalance(withdrawal);
        }

        cash.setQuantity(Quantity.of(cash.getQuantity().getQty() - withdrawal.getAmount().doubleValue()));
        investedBalance = investedBalance.minus(withdrawal);
    }

    private Optional<Asset> findAssetByTicker(Ticker ticker) {
        return assets.stream()
                .filter(asset -> asset.getTicker().equals(ticker))
                .findFirst();
    }

    private Optional<Asset> findAssetByTickerAndSubName(Ticker ticker, SubName subName) {
        return assets.stream()
                .filter(asset -> asset.getTicker().equals(ticker) && asset.getSubName().equals(subName))
                .findFirst();
    }
}
