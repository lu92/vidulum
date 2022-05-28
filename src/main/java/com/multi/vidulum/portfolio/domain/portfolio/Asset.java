package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.*;
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
public class Asset implements Valuable {
    private Ticker ticker;
    private SubName subName;
    private Price avgPurchasePrice;
    private Quantity quantity;
    private Quantity locked;
    private Quantity free;
    private Set<AssetLock> activeLocks;

    @Override
    public Money getValue() {
        return avgPurchasePrice.multiply(quantity);
    }

    public void lock(OrderId orderId, Quantity quantity) {
        locked = locked.plus(quantity);
        free = free.minus(quantity);
        activeLocks.add(new AssetLock(orderId, quantity));
    }

    public void unlock(OrderId orderId, Quantity quantity) {
        locked = locked.minus(quantity);
        free = free.plus(quantity);
        AssetLock assetLock = fetchLock(orderId).orElseThrow(() -> new IllegalArgumentException(""));
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
