package com.multi.vidulum.portfolio.domain.portfolio;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.AmbiguousAssetSelectionException;
import com.multi.vidulum.portfolio.domain.AssetNotFoundException;
import com.multi.vidulum.portfolio.domain.DuplicateAssetPositionException;
import com.multi.vidulum.portfolio.domain.NotSufficientBalance;
import com.multi.vidulum.portfolio.domain.PortfolioIsNotOpenedException;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioEvents.*;
import com.multi.vidulum.portfolio.domain.portfolio.snapshots.PortfolioSnapshot;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import com.multi.vidulum.shared.ddd.Aggregate;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Data
@Slf4j
@Builder
public class Portfolio implements Aggregate<PortfolioId, PortfolioSnapshot> {
    private PortfolioId portfolioId;
    private UserId userId;
    private String name;
    private Broker broker;
    private List<Asset> assets;

    /**
     * Everything that has moved in or out, in order (task C9). Replaces the single
     * {@code investedBalance} this used to carry — see {@link Contribution} for why a scalar could
     * not hold it.
     */
    private List<Contribution> contributions;
    private PortfolioStatus status;
    private Currency allowedDepositCurrency;
    private List<DomainEvent> uncommittedEvents;

    @Override
    public PortfolioSnapshot getSnapshot() {
        List<PortfolioSnapshot.AssetSnapshot> assetSnapshots = assets.stream()
                .map(asset -> {
                    List<PortfolioSnapshot.AssetLockSnapshot> activeLocks = asset.getActiveLocks().stream()
                            .map(assetLock -> new PortfolioSnapshot.AssetLockSnapshot(
                                    assetLock.orderId(),
                                    assetLock.locked()))
                            .collect(Collectors.toList());

                    return new PortfolioSnapshot.AssetSnapshot(
                            asset.getTicker(),
                            asset.getSubName(),
                            asset.getCostBasis(),
                            asset.getQuantity(),
                            asset.getLocked(),
                            asset.getFree(),
                            activeLocks);
                })
                .collect(Collectors.toList());

        return new PortfolioSnapshot(
                portfolioId,
                userId,
                name,
                broker,
                assetSnapshots,
                status,
                List.copyOf(contributions),
                allowedDepositCurrency
        );
    }

    public static Portfolio from(PortfolioSnapshot snapshot) {
        List<Asset> assets = snapshot.getAssets().stream()
                .map(assetSnapshot -> {
                    Set<Asset.AssetLock> activeLocks = assetSnapshot.getActiveLocks().stream()
                            .map(lockSnapshot -> new Asset.AssetLock(
                                    lockSnapshot.orderId(),
                                    lockSnapshot.locked()
                            ))
                            .collect(Collectors.toSet());
                    return new Asset(
                            assetSnapshot.getTicker(),
                            assetSnapshot.getSubName(),
                            assetSnapshot.getCostBasis(),
                            assetSnapshot.getQuantity(),
                            assetSnapshot.getLocked(),
                            assetSnapshot.getFree(),
                            activeLocks
                    );
                })
                .collect(Collectors.toList());

        requireOnePositionPerName(assets);

        return Portfolio.builder()
                .portfolioId(snapshot.getPortfolioId())
                .userId(snapshot.getUserId())
                .name(snapshot.getName())
                .broker(snapshot.getBroker())
                .assets(assets)
                .status(snapshot.getStatus())
                .contributions(new LinkedList<>(snapshot.getContributions()))
                .allowedDepositCurrency(snapshot.getAllowedDepositCurrency())
                .build();
    }


    public void handleExecutedTrade(ExecutedTrade trade) {
        PortfolioEvents.TradeProcessedEvent event = new PortfolioEvents.TradeProcessedEvent(
                trade.getPortfolioId(),
                trade.getTradeId(),
                trade.getOrderId(),
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
            swing(event.orderId(), soldPortion, purchasedPortion);
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
            // The money side of a trade is cash, which is never split by origin.
            return new AssetPortion(
                    event.symbol().getDestination(),
                    SubName.none(),
                    Quantity.of(event.price().multiply(event.quantity()).getAmount().doubleValue()),
                    Price.one(event.symbol().getDestination().getId()));
        } else {
            return new AssetPortion(
                    event.symbol().getOrigin(),
                    tradedPosition(event.subName()),
                    event.quantity(),
                    event.price());
        }
    }

    private AssetPortion calculatePurchasedPortionOfAsset(PortfolioEvents.TradeProcessedEvent trade) {
        if (Side.BUY.equals(trade.side())) {
            return new AssetPortion(
                    trade.symbol().getOrigin(),
                    tradedPosition(trade.subName()),
                    trade.quantity(),
                    trade.price());
        } else {
            return new AssetPortion(
                    trade.symbol().getDestination(),
                    SubName.none(),
                    Quantity.of(trade.price().multiply(trade.quantity()).getAmount().doubleValue()),
                    Price.one(trade.symbol().getDestination().getId()));
        }
    }

    private void swing(OrderId orderId, AssetPortion soldPortion, AssetPortion purchasedPortion) {
        reduceAsset(orderId, soldPortion);
        increaseAsset(purchasedPortion);
        log.info("[{}] amount of reduced asset [{}]", portfolioId, soldPortion);
        log.info("[{}] amount of increased asset [{}]", portfolioId, purchasedPortion);
    }

    private void increaseAsset(AssetPortion purchasedPortion) {
        findAssetByTickerAndSubName(purchasedPortion.ticker(), purchasedPortion.subName())
                .ifPresentOrElse(existingAsset -> {
                    Quantity totalQuantity = existingAsset.getQuantity().plus(purchasedPortion.quantity());
                    Quantity updatedFreeQuantity = existingAsset.getFree().plus(purchasedPortion.quantity());

                    // A trade always tells us what it cost. Merging happens only over the parts
                    // whose cost is known, so adding to a position of unknown origin records the
                    // cost of the new part and leaves the rest uncovered instead of averaging a
                    // real price with an invented one.
                    CostBasis purchasedCost = purchasedCostOf(purchasedPortion);
                    CostBasis updatedCost = existingAsset.hasKnownCost()
                            ? existingAsset.getCostBasis().merge(purchasedCost)
                            : purchasedCost;

                    existingAsset.setQuantity(totalQuantity);
                    existingAsset.setCostBasis(updatedCost);
                    existingAsset.setFree(updatedFreeQuantity);
                }, () -> {
                    Asset newAsset = Asset.builder()
                            .ticker(purchasedPortion.ticker())
                            .subName(purchasedPortion.subName())
                            .costBasis(purchasedCostOf(purchasedPortion))
                            .quantity(purchasedPortion.quantity())
                            // Zero of the same unit the position is measured in. A troy ounce
                            // position whose lock is counted in "Number" reads as a unit error
                            // to anyone inspecting it, and nothing in Quantity would object.
                            .locked(Quantity.zero(purchasedPortion.quantity().getUnit()))
                            .free(purchasedPortion.quantity())
                            .activeLocks(new HashSet<>())
                            .build();
                    addAsset(newAsset);
                });
    }

    /**
     * Takes units out of a position, from whichever side of it they were promised.
     *
     * <p>A trade filled against an order consumes a reservation: the units left {@code free} when
     * the order was placed, and this is where the matching {@code locked} amount is released. A
     * trade entered by hand reserved nothing, so it comes straight out of {@code free}.
     *
     * <p>Treating both alike is what broke the manual path: subtracting from {@code locked} when
     * nothing was locked drove it negative, left {@code free} untouched, and produced a position
     * whose parts no longer summed to its size — silently, on a request that answered 200.
     */
    private void reduceAsset(OrderId orderId, AssetPortion soldPortion) {
        Asset soldAsset = findAssetByTickerAndSubName(soldPortion.ticker(), soldPortion.subName())
                .orElseThrow(() -> new AssetNotFoundException(soldPortion.ticker()));

        boolean fromReservation = OrderId.isDefined(orderId);

        // With an order the units were already set aside, so the whole position backs the trade.
        // Without one, only what is free does — otherwise a hand-entered sale would spend units an
        // open order is holding, and the order could no longer be filled.
        Quantity available = fromReservation ? soldAsset.getQuantity() : soldAsset.getFree();
        if (soldPortion.quantity().getQty() > available.getQty()) {
            throw new NotSufficientBalance(soldPortion.getValue());
        }

        if (soldAsset.getQuantity().equals(soldPortion.quantity())) {
            assets.remove(soldAsset);
            return;
        }

        // The cost can never cover more than is still held, so it is capped rather than
        // recomputed. Which units were sold — the ones with a known cost or the ones
        // without — is a question this task does not answer; C7 owns it. Once C2 splits
        // positions into all-known and all-unknown, both readings coincide.
        Quantity decreasedQuantity = soldAsset.getQuantity().minus(soldPortion.quantity());
        soldAsset.setQuantity(decreasedQuantity);
        soldAsset.setCostBasis(cappedTo(soldAsset.getCostBasis(), decreasedQuantity));

        if (fromReservation) {
            soldAsset.setLocked(soldAsset.getLocked().minus(soldPortion.quantity()));
            soldAsset.getActiveLocks().remove(new Asset.AssetLock(orderId, soldPortion.quantity()));
        } else {
            soldAsset.setFree(soldAsset.getFree().minus(soldPortion.quantity()));
        }
    }

    public void apply(MoneyDepositedEvent event) {
        tryWhenPortfolioIsOpen(() -> {
            Ticker ticker = Ticker.of(event.deposit().getCurrency());
            Currency depositCurrency = Currency.of(ticker.getId());
            if (!depositCurrency.equals(allowedDepositCurrency)) {
                throw new IllegalArgumentException(String.format("Cannot accept deposit with currency: [%s]", depositCurrency));
            }
            findCashAsset(ticker).ifPresentOrElse(existingAsset -> {
                Quantity deposited = Quantity.of(event.deposit().getAmount().doubleValue());
                Quantity updatedQuantity = existingAsset.getQuantity().plus(deposited);

                // Added to what was free, not set to the whole balance. Setting it handed back
                // money an open order had reserved: the position still said 4 000 locked while
                // free jumped to the full 11 000, so the same units could be spent twice and the
                // order they backed could no longer be filled.
                existingAsset.setQuantity(updatedQuantity);
                existingAsset.setFree(existingAsset.getFree().plus(deposited));
                existingAsset.setCostBasis(CostBasis.atPar(updatedQuantity, event.deposit().getCurrency()));
            }, () -> {
                Asset cash = Asset.builder()
                        .ticker(ticker)
                        .subName(SubName.none())
                        .costBasis(CostBasis.atPar(
                                Quantity.of(event.deposit().getAmount().doubleValue()),
                                event.deposit().getCurrency()))
                        .quantity(Quantity.of(event.deposit().getAmount().doubleValue()))
                        .locked(Quantity.zero())
                        .free(Quantity.of(event.deposit().getAmount().doubleValue()))
                        .activeLocks(new HashSet<>())
                        .build();
                addAsset(cash);
            });
            contributions.add(new Contribution(event.contributionId(), event.dateTime(),
                    Contribution.Direction.IN, event.deposit(), event.deposit(), Provenance.ASSUMED_PAR));
        });
    }

    public void depositMoney(Money deposit, ContributionId contributionId, ZonedDateTime dateTime) {
        MoneyDepositedEvent event = new MoneyDepositedEvent(portfolioId, deposit, contributionId, dateTime);
        apply(event);
        add(event);
    }

    public void withdrawMoney(Money withdrawal, ContributionId contributionId, ZonedDateTime dateTime) {
        MoneyWithdrawEvent event = new MoneyWithdrawEvent(portfolioId, withdrawal, contributionId, dateTime);
        apply(event);
        add(event);
    }

    public void apply(MoneyWithdrawEvent event) {
        tryWhenPortfolioIsOpen(() -> {
            Ticker ticker = Ticker.of(event.withdrawal().getCurrency());
            Asset cash = findCashAsset(ticker)
                    .orElseThrow(() -> new AssetNotFoundException(ticker));

            if (cash.getFree().getQty() < event.withdrawal().getAmount().doubleValue()) {
                throw new NotSufficientBalance(event.withdrawal());
            }

            if (cash.getQuantity().getQty() < event.withdrawal().getAmount().doubleValue()) {
                throw new NotSufficientBalance(event.withdrawal());
            }

            Quantity remaining = Quantity.of(cash.getQuantity().getQty() - event.withdrawal().getAmount().doubleValue());
            cash.setQuantity(remaining);
            cash.setFree(Quantity.of(cash.getFree().getQty() - event.withdrawal().getAmount().doubleValue()));
            // Withdrawing shrinks the position, so the cost must shrink with it — otherwise the
            // cost keeps claiming to cover units that are no longer held.
            cash.setCostBasis(cappedTo(cash.getCostBasis(), remaining));
            contributions.add(new Contribution(event.contributionId(), event.dateTime(),
                    Contribution.Direction.OUT, event.withdrawal(), event.withdrawal(), Provenance.ASSUMED_PAR));
        });
    }

    /** A trade's cost is known exactly, because we recorded the fill ourselves. */
    private static CostBasis purchasedCostOf(AssetPortion purchasedPortion) {
        return CostBasis.of(
                purchasedPortion.quantity(),
                purchasedPortion.price(),
                Provenance.DERIVED_FROM_FILLS);
    }

    /** Keeps a known cost from claiming to cover more units than the position still holds. */
    private static CostBasis cappedTo(CostBasis costBasis, Quantity remaining) {
        if (costBasis == null) {
            return null;
        }
        return costBasis.quantity().getQty() > remaining.getQty()
                ? costBasis.reduceTo(remaining)
                : costBasis;
    }

    /**
     * The cash position of a currency. Money is never split by origin, so this is unambiguous by
     * construction — unlike a lookup by ticker alone, which C2 removed.
     */
    private Optional<Asset> findCashAsset(Ticker ticker) {
        return findAssetByTickerAndSubName(ticker, SubName.none());
    }

    private Optional<Asset> findAssetByTickerAndSubName(Ticker ticker, SubName subName) {
        return assets.stream()
                .filter(asset -> asset.getTicker().equals(ticker) && asset.getSubName().equals(subName))
                .findFirst();
    }

    /** Every position of a ticker. What views and valuation need, instead of an arbitrary first. */
    public List<Asset> findAssetsByTicker(Ticker ticker) {
        return assets.stream()
                .filter(asset -> asset.getTicker().equals(ticker))
                .toList();
    }

    /**
     * Resolves which position an operation meant.
     *
     * <p>When {@code subName} is given, that position is used. When it is not, the ticker must be
     * held in exactly one position; holding it in several and not saying which is an
     * {@link AmbiguousAssetSelectionException}, never a silent pick by list order.
     */
    private Asset requireAsset(Ticker ticker, SubName subName) {
        if (subName != null) {
            return findAssetByTickerAndSubName(ticker, subName)
                    .orElseThrow(() -> new AssetNotFoundException(ticker));
        }
        List<Asset> candidates = findAssetsByTicker(ticker);
        if (candidates.isEmpty()) {
            throw new AssetNotFoundException(ticker);
        }
        if (candidates.size() > 1) {
            throw new AmbiguousAssetSelectionException(
                    ticker, candidates.stream().map(Asset::getSubName).toList());
        }
        return candidates.getFirst();
    }

    /**
     * A trade always concerns the traded position; {@code none} on the non-cash side is the
     * pre-C2 default and is translated rather than creating a third, empty position.
     */
    private static SubName tradedPosition(SubName requested) {
        return requested == null || requested.isCash() ? SubName.traded() : requested;
    }

    /**
     * The same guard as {@link #addAsset}, applied when a portfolio is rehydrated.
     *
     * <p>Without it the invariant would hold only for positions this aggregate created, and a
     * document written around it — by a migration, a fixture, or the synchronisation engine —
     * could load two positions under one key. Everything downstream assumes that key identifies
     * exactly one row.
     */
    private static void requireOnePositionPerName(List<Asset> assets) {
        Set<String> seen = new HashSet<>();
        assets.forEach(asset -> {
            String key = asset.getTicker().getId() + "/" + asset.getSubName().getName();
            if (!seen.add(key)) {
                throw new DuplicateAssetPositionException(asset.getTicker(), asset.getSubName());
            }
        });
    }

    /** Guards the invariant every other rule rests on: one position per (ticker, subName). */
    private void addAsset(Asset asset) {
        findAssetByTickerAndSubName(asset.getTicker(), asset.getSubName()).ifPresent(existing -> {
            throw new DuplicateAssetPositionException(asset.getTicker(), asset.getSubName());
        });
        assets.add(asset);
    }

    public void lockAsset(Ticker ticker, OrderId orderId, Quantity quantity, ZonedDateTime dateTime) {
        lockAsset(ticker, null, orderId, quantity, dateTime);
    }

    public void lockAsset(Ticker ticker, SubName subName, OrderId orderId, Quantity quantity, ZonedDateTime dateTime) {
        SubName resolved = requireAsset(ticker, subName).getSubName();
        AssetLockedEvent event = new AssetLockedEvent(portfolioId, ticker, resolved, orderId, quantity, dateTime);
        apply(event);
        add(event);
    }

    public void apply(AssetLockedEvent event) {
        tryWhenPortfolioIsOpen(() -> {
            Asset asset = requireAsset(event.ticker(), event.subName());
            asset.lock(event.orderId(), event.quantity());
        });
    }

    public void unlockAsset(Ticker ticker, OrderId orderId, Quantity quantity, ZonedDateTime dateTime) {
        unlockAsset(ticker, null, orderId, quantity, dateTime);
    }

    public void unlockAsset(Ticker ticker, SubName subName, OrderId orderId, Quantity quantity, ZonedDateTime dateTime) {
        SubName resolved = requireAsset(ticker, subName).getSubName();
        AssetUnlockedEvent event = new AssetUnlockedEvent(portfolioId, ticker, resolved, orderId, quantity, dateTime);
        apply(event);
        add(event);
    }

    public void apply(AssetUnlockedEvent event) {
        tryWhenPortfolioIsOpen(() -> {

            Asset asset = requireAsset(event.ticker(), event.subName());
            asset.unlock(event.orderId(), event.quantity());
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
