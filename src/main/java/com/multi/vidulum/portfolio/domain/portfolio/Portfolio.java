package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioEvents;
import com.multi.vidulum.portfolio.app.PortfolioEvents.AssetLockedEvent;
import com.multi.vidulum.portfolio.app.PortfolioEvents.AssetUnlockedEvent;
import com.multi.vidulum.portfolio.app.PortfolioEvents.MoneyDepositedEvent;
import com.multi.vidulum.portfolio.app.PortfolioEvents.MoneyWithdrawEvent;
import com.multi.vidulum.portfolio.domain.AssetNotFoundException;
import com.multi.vidulum.portfolio.domain.NotSufficientBalance;
import com.multi.vidulum.portfolio.domain.portfolio.snapshots.PortfolioSnapshot;
import com.multi.vidulum.portfolio.domain.trades.AssetPortion;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import com.multi.vidulum.shared.ddd.Aggregate;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import lombok.Builder;
import lombok.Data;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
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
    private List<DomainEvent> uncommittedEvents;

    @Override
    public PortfolioSnapshot getSnapshot() {
        List<PortfolioSnapshot.AssetSnapshot> assetSnapshots = assets.stream()
                .map(asset -> new PortfolioSnapshot.AssetSnapshot(
                        asset.getTicker(),
                        asset.getSubName(),
                        asset.getAvgPurchasePrice(),
                        asset.getQuantity(),
                        asset.getLocked(),
                        asset.getFree()))
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
                        assetSnapshot.getSubName(),
                        assetSnapshot.getAvgPurchasePrice(),
                        assetSnapshot.getQuantity(),
                        assetSnapshot.getLocked(),
                        assetSnapshot.getFree()
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


    public void handleExecutedTrade(ExecutedTrade trade) {
        PortfolioEvents.TradeProcessedEvent event = new PortfolioEvents.TradeProcessedEvent(
                trade.getPortfolioId(),
                trade.getTradeId(),
                trade.getSymbol(),
                trade.getSubName(),
                trade.getSide(),
                trade.getQuantity(),
                trade.getPrice()
        );
        apply(event);
        add(event);
    }

    public void apply(PortfolioEvents.TradeProcessedEvent event) {
        AssetPortion purchasedPortion = calculatePurchasedPortionOfAsset(event);
        AssetPortion soldPortion = calculateSoldPortionOfAsset(event);
        swing(soldPortion, purchasedPortion);
    }

    private AssetPortion calculateSoldPortionOfAsset(PortfolioEvents.TradeProcessedEvent event) {
        if (Side.BUY.equals(event.side())) {
            return AssetPortion.builder()
                    .ticker(event.symbol().getDestination())
                    .subName(SubName.none())
                    .quantity(Quantity.of(event.price().multiply(event.quantity().getQty()).getAmount().doubleValue()))
                    .price(Price.one("USD"))
                    .build();
        } else {
            return AssetPortion.builder()
                    .ticker(event.symbol().getOrigin())
                    .subName(event.subName())
                    .quantity(event.quantity())
                    .price(event.price())
                    .build();
        }
    }

    private AssetPortion calculatePurchasedPortionOfAsset(PortfolioEvents.TradeProcessedEvent trade) {
        if (Side.BUY.equals(trade.side())) {
            return AssetPortion.builder()
                    .ticker(trade.symbol().getOrigin())
                    .subName(trade.subName())
                    .quantity(trade.quantity())
                    .price(trade.price())
                    .build();
        } else {
            return AssetPortion.builder()
                    .ticker(trade.symbol().getDestination())
                    .subName(SubName.none())
                    .quantity(Quantity.of(trade.price().multiply(trade.quantity().getQty()).getAmount().doubleValue()))
                    .price(Price.one("USD"))
                    .build();
        }
    }

    private void swing(AssetPortion soldPortion, AssetPortion purchasedPortion) {
        reduceAsset(soldPortion);
        increaseAsset(purchasedPortion);
    }

    private void increaseAsset(AssetPortion purchasedPortion) {
        findAssetByTickerAndSubName(purchasedPortion.getTicker(), purchasedPortion.getSubName())
                .ifPresentOrElse(existingAsset -> {
                    Quantity totalQuantity = existingAsset.getQuantity().plus(purchasedPortion.getQuantity());
                    Quantity updatedFreeQuantity = existingAsset.getFree().plus(purchasedPortion.getQuantity());
                    Money totalValue = existingAsset.getValue().plus(purchasedPortion.getValue());
                    Price updatedAvgPurchasePrice = Price.of(totalValue.divide(totalQuantity));

                    existingAsset.setQuantity(totalQuantity);
                    existingAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
                    existingAsset.setFree(updatedFreeQuantity);
                }, () -> {
                    Asset newAsset = Asset.builder()
                            .ticker(purchasedPortion.getTicker())
                            .subName(purchasedPortion.getSubName())
                            .avgPurchasePrice(purchasedPortion.getPrice())
                            .quantity(purchasedPortion.getQuantity())
                            .locked(Quantity.zero())
                            .free(purchasedPortion.getQuantity())
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
        Quantity decreasedFree = soldAsset.getFree().minus(soldPortion.getQuantity());
        boolean isAssetSoldOutFully = soldAsset.getQuantity().equals(soldPortion.getQuantity());
        if (isAssetSoldOutFully) {
            assets.remove(soldAsset);
        } else {
            Money totalValue = soldAsset.getValue().minus(soldPortion.getValue());
            Price updatedAvgPurchasePrice = Price.of(totalValue.divide(decreasedQuantity));

            soldAsset.setQuantity(decreasedQuantity);
            soldAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
            soldAsset.setFree(decreasedFree);
        }
    }

    public void apply(MoneyDepositedEvent event) {
        Ticker ticker = Ticker.of(event.deposit().getCurrency());
        findAssetByTicker(ticker).ifPresentOrElse(existingAsset -> {
            Quantity updatedQuantity = Quantity.of(existingAsset.getQuantity().getQty() + event.deposit().getAmount().doubleValue());
            existingAsset.setQuantity(updatedQuantity);
            existingAsset.setFree(updatedQuantity);
        }, () -> {
            Asset cash = Asset.builder()
                    .ticker(ticker)
                    .subName(SubName.none())
                    .avgPurchasePrice(Price.one(event.deposit().getCurrency()))
                    .quantity(Quantity.of(event.deposit().getAmount().doubleValue()))
                    .locked(Quantity.zero())
                    .free(Quantity.of(event.deposit().getAmount().doubleValue()))
                    .build();
            assets.add(cash);
        });
        investedBalance = investedBalance.plus(event.deposit());
    }

    public void depositMoney(Money deposit) {
        MoneyDepositedEvent event = new MoneyDepositedEvent(portfolioId, deposit);
        apply(event);
        add(event);
    }

    public void withdrawMoney(Money withdrawal) {
        MoneyWithdrawEvent event = new MoneyWithdrawEvent(portfolioId, withdrawal);
        apply(event);
        add(event);
    }

    public void apply(MoneyWithdrawEvent event) {
        Ticker ticker = Ticker.of(event.withdrawal().getCurrency());
        Asset cash = findAssetByTicker(ticker)
                .orElseThrow(() -> new AssetNotFoundException(ticker));

        if (cash.getFree().getQty() < event.withdrawal().getAmount().doubleValue()) {
            throw new NotSufficientBalance(event.withdrawal());
        }

        if (cash.getQuantity().getQty() < event.withdrawal().getAmount().doubleValue()) {
            throw new NotSufficientBalance(event.withdrawal());
        }

        cash.setQuantity(Quantity.of(cash.getQuantity().getQty() - event.withdrawal().getAmount().doubleValue()));
        cash.setQuantity(Quantity.of(cash.getFree().getQty() - event.withdrawal().getAmount().doubleValue()));
        investedBalance = investedBalance.minus(event.withdrawal());
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

    public void lockAsset(Ticker ticker, Quantity quantity) {
        findAssetByTicker(ticker)
                .orElseThrow(() -> new AssetNotFoundException(ticker));
        AssetLockedEvent event = new AssetLockedEvent(portfolioId, ticker, quantity);
        apply(event);
        add(event);
    }

    public void apply(AssetLockedEvent event) {
        Asset asset = findAssetByTicker(event.ticker())
                .orElseThrow(() -> new AssetNotFoundException(event.ticker()));
        asset.lock(event.quantity());
    }

    public void unlockAsset(Ticker ticker, Quantity quantity) {
        findAssetByTicker(ticker)
                .orElseThrow(() -> new AssetNotFoundException(ticker));

        AssetUnlockedEvent event = new AssetUnlockedEvent(portfolioId, ticker, quantity);
        apply(event);
        add(event);
    }

    public void apply(AssetUnlockedEvent event) {
        Asset asset = findAssetByTicker(event.ticker())
                .orElseThrow(() -> new AssetNotFoundException(event.ticker()));
        asset.unlock(event.quantity());
    }

    public List<DomainEvent> getUncommittedEvents() {
        if (Objects.isNull(uncommittedEvents)) {
            uncommittedEvents = new LinkedList<>();
        }
        return uncommittedEvents;
    }

    private void add(DomainEvent event) {
        // store event temporary
        getUncommittedEvents().add(event);
    }
}
