package com.multi.vidulum.portfolio.domain.portfolio.snapshots;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;
import lombok.Value;

import java.util.List;

@Value
public class PortfolioSnapshot implements EntitySnapshot<PortfolioId> {

    PortfolioId portfolioId;
    UserId userId;
    String name;
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
        Money avgPurchasePrice;
        double quantity;
        List<String> tags;
    }
}
