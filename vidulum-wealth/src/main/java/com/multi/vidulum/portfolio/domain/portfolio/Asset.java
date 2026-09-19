package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.CannotUnlockAssetException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Optional;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Asset {
    private Ticker ticker;
    private SubName subName;

    /**
     * What the position cost — for the part of it we know about. {@code null} means the cost is
     * unknown, which is a legitimate state: assets transferred in from outside carry no purchase
     * price, and inventing one would turn a guess into reported profit.
     */
    private CostBasis costBasis;

    private Quantity quantity;
    private Quantity locked;
    private Quantity free;
    private Set<AssetLock> activeLocks;

    /**
     * What the known part of this position cost, or empty when no cost is known.
     *
     * <p>Replaces the old {@code getValue()}, which multiplied the average price by the
     * <b>whole</b> quantity. That was the shape of the bug: hold 100 units, know the cost of 0.3,
     * and every consumer got an invented number. {@code Optional} forces each caller to decide
     * what to do about a missing cost instead of receiving a fabricated zero.
     */
    public Optional<Money> knownCost() {
        return Optional.ofNullable(costBasis).map(CostBasis::totalCost);
    }

    /** How much of this position has a known cost. Zero when none of it does. */
    public Quantity coveredQuantity() {
        return costBasis != null ? costBasis.quantity() : Quantity.zero(quantity.getUnit());
    }

    public boolean hasKnownCost() {
        return costBasis != null;
    }

    public void lock(OrderId orderId, Quantity quantity) {
        locked = locked.plus(quantity);
        free = free.minus(quantity);
        activeLocks.add(new AssetLock(orderId, quantity));
    }

    public void unlock(OrderId orderId, Quantity quantity) {
        locked = locked.minus(quantity);
        free = free.plus(quantity);
        AssetLock assetLock = fetchLock(orderId)
                .orElseThrow(() -> CannotUnlockAssetException.incorrectOrder(ticker, orderId));

        if (assetLock.locked().minus(quantity).isNegative()) {
            throw CannotUnlockAssetException.insufficientQuantity(ticker, orderId, quantity);
        }

        AssetLock updatedLock = new AssetLock(orderId, assetLock.locked().minus(quantity));
        activeLocks.remove(assetLock);
        if (updatedLock.locked().isPositive()) {
            activeLocks.add(updatedLock);
        }
    }

    private Optional<AssetLock> fetchLock(OrderId orderId) {
        return activeLocks.stream()
                .filter(assetLock -> assetLock.orderId().equals(orderId))
                .findFirst();
    }

    public record AssetLock(
            OrderId orderId,
            Quantity locked) {
    }
}
