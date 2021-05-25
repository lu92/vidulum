package com.multi.vidulum.portfolio.domain.portfolio.snapshots;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Value;

import java.util.List;

@Value
public class PortfolioSnapshot implements EntitySnapshot<PortfolioId> {

    PortfolioId portfolioId;
    UserId userId;
    String name;
    Broker broker;
    List<AssetSnapshot> assets;
    Money investedBalance;

    @Override
    public PortfolioId id() {
        return portfolioId;
    }

    @Value
    public static class AssetSnapshot {
        Ticker ticker;
        String fullName;
        String subName;
        Money avgPurchasePrice;
        Quantity quantity;
        List<String> tags;
    }
}
