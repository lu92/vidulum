package com.multi.vidulum.portfolio.domain.portfolio;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.snapshots.PortfolioSnapshot;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.util.LinkedList;
import java.util.List;

public class PortfolioFactory {

    public Portfolio empty(PortfolioId portfolioId, String name, UserId userId, Broker broker, Currency allowedDepositCurrency) {
        List<DomainEvent> uncommittedEvents = new LinkedList<>();
        uncommittedEvents.add(
                new PortfolioEvents.PortfolioOpenedEvent(
                        portfolioId,
                        name,
                        broker
                )
        );
        return Portfolio.builder()
                .portfolioId(portfolioId)
                .userId(userId)
                .name(name)
                .broker(broker)
                .assets(new LinkedList<>())
                .allowedDepositCurrency(allowedDepositCurrency)
                .investedBalance(Money.zero(allowedDepositCurrency.getId()))
                .status(PortfolioStatus.OPEN)
                .uncommittedEvents(uncommittedEvents)
                .build();
    }

    /**
     * Opens a portfolio that already holds something — the shape onboarding produces.
     *
     * <p>Until now the only way in was {@link #empty}, with assets arriving through deposits and
     * trades. That works while everything comes from activity we recorded; it cannot express a
     * holding transferred in from outside, whose cost nobody knows. Each position carries its own
     * {@code CostBasis} or none at all, so nothing here has to invent a price.
     *
     * <p>{@code investedBalance} stays zero: it is moved only by deposits and withdrawals, and
     * this path bypasses both. That is a deliberate gap with a task of its own (C9) — a portfolio
     * built from a snapshot will report "invested 0" until it is closed, and the interface must
     * not show that as a fact.
     */
    public Portfolio withAssets(
            PortfolioId portfolioId,
            String name,
            UserId userId,
            Broker broker,
            Currency allowedDepositCurrency,
            List<Asset> assets) {

        List<DomainEvent> uncommittedEvents = new LinkedList<>();
        uncommittedEvents.add(new PortfolioEvents.PortfolioOpenedEvent(portfolioId, name, broker));

        return Portfolio.from(new PortfolioSnapshot(
                portfolioId,
                userId,
                name,
                broker,
                assets.stream().map(PortfolioFactory::toSnapshot).toList(),
                PortfolioStatus.OPEN,
                Money.zero(allowedDepositCurrency.getId()),
                allowedDepositCurrency));
    }

    private static PortfolioSnapshot.AssetSnapshot toSnapshot(Asset asset) {
        return new PortfolioSnapshot.AssetSnapshot(
                asset.getTicker(),
                asset.getSubName(),
                asset.getCostBasis(),
                asset.getQuantity(),
                asset.getLocked(),
                asset.getFree(),
                List.of());
    }
}
