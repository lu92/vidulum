package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.AssetNotFoundException;
import com.multi.vidulum.portfolio.domain.NotSufficientBalance;
import com.multi.vidulum.portfolio.domain.PortfolioIsNotOpenedException;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioEvents.*;
import com.multi.vidulum.portfolio.domain.portfolio.snapshots.PortfolioSnapshot;
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
    private PortfolioStatus status;
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
                status,
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
                .status(snapshot.getStatus())
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
        tryWhenPortfolioIsOpen(() -> {
            AssetPortion purchasedPortion = calculatePurchasedPortionOfAsset(event);
            AssetPortion soldPortion = calculateSoldPortionOfAsset(event);
            swing(soldPortion, purchasedPortion);
        });
    }

    private void tryWhenPortfolioIsOpen(Runnable action) {
        if (isOpened()) {
            action.run();
        } else {
            throw new PortfolioIsNotOpenedException(portfolioId, status);
        }
    }

    private AssetPortion calculateSoldPortionOfAsset(PortfolioEvents.TradeProcessedEvent event) {
        if (Side.BUY.equals(event.side())) {
            return new AssetPortion(
                    event.symbol().getDestination(),
                    SubName.none(),
                    Quantity.of(event.price().multiply(event.quantity()).getAmount().doubleValue()),
                    Price.one("USD"));
        } else {
            return new AssetPortion(
                    event.symbol().getOrigin(),
                    event.subName(),
                    event.quantity(),
                    event.price());
        }
    }

    private AssetPortion calculatePurchasedPortionOfAsset(PortfolioEvents.TradeProcessedEvent trade) {
        if (Side.BUY.equals(trade.side())) {
            return new AssetPortion(
                    trade.symbol().getOrigin(),
                    trade.subName(),
                    trade.quantity(),
                    trade.price());
        } else {
            return new AssetPortion(
                    trade.symbol().getDestination(),
                    SubName.none(),
                    Quantity.of(trade.price().multiply(trade.quantity()).getAmount().doubleValue()),
                    Price.one("USD"));
        }
    }

    private void swing(AssetPortion soldPortion, AssetPortion purchasedPortion) {
        reduceAsset(soldPortion);
        increaseAsset(purchasedPortion);
    }

    private void increaseAsset(AssetPortion purchasedPortion) {
        findAssetByTickerAndSubName(purchasedPortion.ticker(), purchasedPortion.subName())
                .ifPresentOrElse(existingAsset -> {
                    Quantity totalQuantity = existingAsset.getQuantity().plus(purchasedPortion.quantity());
                    Quantity updatedFreeQuantity = existingAsset.getFree().plus(purchasedPortion.quantity());
                    Money totalValue = existingAsset.getValue().plus(purchasedPortion.getValue());
                    Price updatedAvgPurchasePrice = Price.of(totalValue.divide(totalQuantity));

                    existingAsset.setQuantity(totalQuantity);
                    existingAsset.setAvgPurchasePrice(updatedAvgPurchasePrice);
                    existingAsset.setFree(updatedFreeQuantity);
                }, () -> {
                    Asset newAsset = Asset.builder()
                            .ticker(purchasedPortion.ticker())
                            .subName(purchasedPortion.subName())
                            .avgPurchasePrice(purchasedPortion.price())
                            .quantity(purchasedPortion.quantity())
                            .locked(Quantity.zero())
                            .free(purchasedPortion.quantity())
                            .build();
                    assets.add(newAsset);
                });
    }

    private void reduceAsset(AssetPortion soldPortion) {
        Asset soldAsset = findAssetByTickerAndSubName(soldPortion.ticker(), soldPortion.subName())
                .orElseThrow(() -> new AssetNotFoundException(soldPortion.ticker()));

        if (soldPortion.quantity().getQty() > soldAsset.getQuantity().getQty()) {
            throw new NotSufficientBalance(soldPortion.getValue());
        }

        Quantity decreasedQuantity = soldAsset.getQuantity().minus(soldPortion.quantity());
        Quantity decreasedFree = soldAsset.getFree().minus(soldPortion.quantity());
        boolean isAssetSoldOutFully = soldAsset.getQuantity().equals(soldPortion.quantity());
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
        tryWhenPortfolioIsOpen(() -> {
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
        });
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
        tryWhenPortfolioIsOpen(() -> {
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
            cash.setFree(Quantity.of(cash.getFree().getQty() - event.withdrawal().getAmount().doubleValue()));
            investedBalance = investedBalance.minus(event.withdrawal());
        });
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
        tryWhenPortfolioIsOpen(() -> {
            Asset asset = findAssetByTicker(event.ticker())
                    .orElseThrow(() -> new AssetNotFoundException(event.ticker()));
            asset.lock(event.quantity());
        });
    }

    public void unlockAsset(Ticker ticker, Quantity quantity) {
        findAssetByTicker(ticker)
                .orElseThrow(() -> new AssetNotFoundException(ticker));

        AssetUnlockedEvent event = new AssetUnlockedEvent(portfolioId, ticker, quantity);
        apply(event);
        add(event);
    }

    public void apply(AssetUnlockedEvent event) {
        tryWhenPortfolioIsOpen(() -> {

            Asset asset = findAssetByTicker(event.ticker())
                    .orElseThrow(() -> new AssetNotFoundException(event.ticker()));
            asset.unlock(event.quantity());
        });
    }

    public void close() {
        PortfolioClosedEvent event = new PortfolioClosedEvent(portfolioId, userId);
        apply(event);
        add(event);
    }

    public void apply(PortfolioClosedEvent event) {
        status = PortfolioStatus.CLOSED;
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

    public boolean isOpened() {
        return PortfolioStatus.OPEN.equals(status);
    }

    private record AssetPortion(
            Ticker ticker,
            SubName subName,
            Quantity quantity,
            Price price) {

        public Money getValue() {
            return price.multiply(quantity);
        }
    }
}
