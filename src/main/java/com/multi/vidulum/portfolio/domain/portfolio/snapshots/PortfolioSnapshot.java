package com.multi.vidulum.portfolio.domain.portfolio.snapshots;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Value;

import java.time.ZonedDateTime;
import java.util.List;

@Value
public class PortfolioSnapshot implements EntitySnapshot<PortfolioId> {

    PortfolioId portfolioId;
    UserId userId;
    String name;
    Broker broker;
    List<AssetSnapshot> assets;
    PortfolioStatus status;
    Money investedBalance;
    Currency allowedDepositCurrency;

    @Override
    public PortfolioId id() {
        return portfolioId;
    }

    @Value
    public static class AssetSnapshot {
        Ticker ticker;
        SubName subName;
        Price avgPurchasePrice;
        Quantity quantity;
        Quantity locked;
        Quantity free;
        List<AssetLockSnapshot> activeLocks;
    }

    public record AssetLockSnapshot(
            OrderId orderId,
            Quantity locked) {
    }
}
