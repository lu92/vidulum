package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
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

//    public void handleExecutedTrade(BuyTrade trade, AssetBasicInfo assetBasicInfo) {
//        findAssetByTicker(trade.getSymbol().getOrigin())
//                .ifPresentOrElse(existingAsset -> {
//                    double totalQuantity = existingAsset.getQuantity() + trade.getQuantity();
//                    Money totalValue = existingAsset.getValue().plus(trade.getValue());
//                    Money updatedAvgPurchasePrice = totalValue.divide(totalQuantity);
//
//                    existingAsset.setQuantity(totalQuantity);
//                    existingAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
//                }, () -> {
//                    Asset newAsset = Asset.builder()
//                            .ticker(assetBasicInfo.getTicker())
//                            .fullName(assetBasicInfo.getFullName())
//                            .avgPurchasePrice(trade.getPrice())
//                            .quantity(trade.getQuantity())
//                            .tags(assetBasicInfo.getTags())
//                            .build();
//                    Money investment = trade.getPrice().multiply(trade.getQuantity());
//                    investedBalance = investedBalance.plus(investment);
//                    assets.add(newAsset);
//                });
//    }


    public void handleExecutedTrade(BuyTrade trade, AssetBasicInfo assetBasicInfo) {
        AssetPortion soldPortion = trade.clarifySoldPortion();
        AssetPortion purchasedPortion = trade.clarifyPurchasedPortion();
        swing(soldPortion, purchasedPortion, assetBasicInfo);
    }

    public void handleExecutedTrade2(SellTrade trade, AssetBasicInfo assetBasicInfo) {
        AssetPortion soldPortion = trade.clarifySoldPortion();
        AssetPortion purchasedPortion = trade.clarifyPurchasedPortion();
        swing(soldPortion, purchasedPortion, assetBasicInfo);
    }

    private void swing(AssetPortion soldPortion, AssetPortion purchasedPortion, AssetBasicInfo purchasedAssetBasicInfo) {
        reduceAsset(soldPortion);
        increaseAsset(purchasedPortion, purchasedAssetBasicInfo);
    }

    private void increaseAsset(AssetPortion purchasedPortion, AssetBasicInfo assetBasicInfo) {
        findAssetByTicker(purchasedPortion.getTicker())
                .ifPresentOrElse(existingAsset -> {
                    double totalQuantity = existingAsset.getQuantity() + purchasedPortion.getQuantity();
                    Money totalValue = existingAsset.getValue().plus(purchasedPortion.getValue());
                    Money updatedAvgPurchasePrice = totalValue.divide(totalQuantity);

                    existingAsset.setQuantity(totalQuantity);
                    existingAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
                }, () -> {
                    Asset newAsset = Asset.builder()
                            .ticker(assetBasicInfo.getTicker())
                            .fullName(assetBasicInfo.getFullName())
                            .avgPurchasePrice(purchasedPortion.getPrice())
                            .quantity(purchasedPortion.getQuantity())
                            .tags(assetBasicInfo.getTags())
                            .build();
                    assets.add(newAsset);
                });
    }

    private void reduceAsset(AssetPortion soldPortion) {
        Asset soldAsset = findAssetByTicker(soldPortion.getTicker())
                .orElseThrow(() -> new AssetNotFoundException(soldPortion.getTicker()));

        if (soldPortion.getQuantity() > soldAsset.getQuantity()) {
            throw new NotSufficientBalance(soldPortion.getValue());
        }

        double decreasedQuantity = soldAsset.getQuantity() - soldPortion.getQuantity();
        if (isAssetSoldOutFully(soldAsset, soldPortion)) {
            assets.remove(soldAsset);
        } else {
            Money totalValue = soldAsset.getValue().minus(soldPortion.getValue());
            Money updatedAvgPurchasePrice = totalValue.divide(decreasedQuantity);

            soldAsset.setQuantity(decreasedQuantity);
            soldAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
        }
    }

    public void handleExecutedTrade(SellTrade trade) {
        Asset soldAsset = findAssetByTicker(trade.getSymbol().getOrigin())
                .orElseThrow(() -> new AssetNotFoundException(trade.getSymbol().getOrigin()));

        double totalQuantity = soldAsset.getQuantity() - trade.getQuantity();
        if (isAssetSoldOutFully(soldAsset, trade)) {
            assets.remove(soldAsset);
        } else {
            Money totalValue = soldAsset.getValue().minus(trade.getValue());
            Money updatedAvgPurchasePrice = totalValue.divide(totalQuantity);

            soldAsset.setQuantity(totalQuantity);
            soldAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
        }
        appendMoneyToAssets(trade.getValue());
    }

    private boolean isAssetSoldOutFully(Asset soldAsset, SellTrade trade) {
        return soldAsset.getQuantity() == trade.getQuantity();
    }

    private boolean isAssetSoldOutFully(Asset soldAsset, AssetPortion assetPortion) {
        return soldAsset.getQuantity() == assetPortion.getQuantity();
    }

    public void depositMoney(Money deposit) {
        appendMoneyToAssets(deposit);
        investedBalance = investedBalance.plus(deposit);
    }

    public void withdrawMoney(Money withdrawal) {
        withdrawMoneyFromAssets(withdrawal);
        investedBalance = investedBalance.minus(withdrawal);
    }

    private Optional<Asset> findAssetByTicker(Ticker ticker) {
        return assets.stream()
                .filter(asset -> asset.getTicker().equals(ticker))
                .findFirst();
    }

    private void appendMoneyToAssets(Money deposit) {
        Ticker ticker = Ticker.of(deposit.getCurrency());
        findAssetByTicker(ticker).ifPresentOrElse(existingAsset -> {
            double updatedQuantity = existingAsset.getQuantity() + deposit.getAmount().doubleValue();
            existingAsset.setQuantity(updatedQuantity);
        }, () -> {
            Asset cash = Asset.builder()
                    .ticker(ticker)
                    .fullName("")
                    .avgPurchasePrice(Money.one(deposit.getCurrency()))
                    .quantity(deposit.getAmount().doubleValue())
                    .tags(List.of("currency", deposit.getCurrency()))
                    .build();
            assets.add(cash);
        });
    }

    private void withdrawMoneyFromAssets(Money withdrawal) {
        Ticker ticker = Ticker.of(withdrawal.getCurrency());
        Asset cash = findAssetByTicker(ticker)
                .orElseThrow(() -> new AssetNotFoundException(ticker));

        if (cash.getQuantity() < withdrawal.getAmount().doubleValue()) {
            throw new NotSufficientBalance(withdrawal);
        }

        cash.setQuantity(cash.getQuantity() - withdrawal.getAmount().doubleValue());
    }
}
