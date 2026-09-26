package com.multi.vidulum.portfolio.domain.portfolio.snapshots;

import com.multi.vidulum.portfolio.domain.portfolio.Contribution;
import com.multi.vidulum.portfolio.domain.portfolio.RealisedResult;
import com.multi.vidulum.common.*;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Value;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

@Value
public class PortfolioSnapshot implements EntitySnapshot<PortfolioId> {

    PortfolioId portfolioId;
    UserId userId;
    String name;
    Broker broker;
    List<AssetSnapshot> assets;
    PortfolioStatus status;
    List<Contribution> contributions;
    List<RealisedResult> realisedResults;
    Set<TradeId> appliedTrades;
    Currency allowedDepositCurrency;

    @Override
    public PortfolioId id() {
        return portfolioId;
    }

    @Value
    public static class AssetSnapshot {
        Ticker ticker;
        SubName subName;
        CostBasis costBasis;
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
