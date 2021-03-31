package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.AssetNotFoundException;
import com.multi.vidulum.portfolio.domain.NotSufficientBalance;
import com.multi.vidulum.portfolio.domain.portfolio.snapshots.PortfolioSnapshot;
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
                .assets(assets)
                .investedBalance(snapshot.getInvestedBalance())
                .build();
    }

    public void handleExecutedTrade(BuyTrade trade, AssetBasicInfo assetBasicInfo) {

        Ticker currencyTicker = Ticker.of(trade.getPrice().getCurrency());
        findAssetByTicker(currencyTicker)
                .orElseThrow(() -> new NotSufficientBalance(trade.getValue()));

        findAssetByTicker(trade.getTicker())
                .ifPresentOrElse(existingAsset -> {

                    double totalQuantity = existingAsset.getQuantity() + trade.getQuantity();
                    Money totalValue = existingAsset.getValue().plus(trade.getValue());
                    Money updatedAvgPurchasePrice = totalValue.divide(totalQuantity);

                    existingAsset.setQuantity(totalQuantity);
                    existingAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
                }, () -> {
                    Asset newAsset = Asset.builder()
                            .ticker(assetBasicInfo.getTicker())
                            .fullName(assetBasicInfo.getFullName())
                            .avgPurchasePrice(trade.getPrice())
                            .quantity(trade.getQuantity())
                            .tags(assetBasicInfo.getTags())
                            .build();
                    assets.add(newAsset);
                });
    }

    public void handleExecutedTrade(SellTrade trade) {
        Asset sellingAsset = findAssetByTicker(trade.getTicker())
                .orElseThrow(() -> new AssetNotFoundException(trade.getTicker()));

        double totalQuantity = sellingAsset.getQuantity() - trade.getQuantity();
        Money totalValue = sellingAsset.getValue().minus(trade.getValue());
        Money updatedAvgPurchasePrice = totalValue.divide(totalQuantity);

        sellingAsset.setQuantity(totalQuantity);
        sellingAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
        appendMoneyToAssets(trade.getValue());
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
            existingAsset.setQuantity(existingAsset.getQuantity() + deposit.getAmount().doubleValue());
        }, () -> {
            Asset cash = Asset.builder()
                    .ticker(ticker)
                    .fullName("")
                    .avgPurchasePrice(Money.zero(deposit.getCurrency()))
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
