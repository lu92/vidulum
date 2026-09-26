package com.multi.vidulum.portfolio.domain.portfolio;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.snapshots.PortfolioSnapshot;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

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
                .contributions(new LinkedList<>())
                .realisedResults(new LinkedList<>())
                .appliedTrades(new java.util.LinkedHashSet<>())
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
     * <p>The opening contribution comes in with the assets (task C12): a portfolio built from a
     * snapshot did not get here through deposits, so its ledger starts with one entry saying what
     * the account was worth on the day we first read it — not with a zero pretending nobody ever
     * put anything in.
     */
    public Portfolio withAssets(
            PortfolioId portfolioId,
            String name,
            UserId userId,
            Broker broker,
            Currency allowedDepositCurrency,
            List<Asset> assets,
            List<Contribution> contributions) {

        List<DomainEvent> uncommittedEvents = new LinkedList<>();
        uncommittedEvents.add(new PortfolioEvents.PortfolioOpenedEvent(portfolioId, name, broker));

        return Portfolio.from(new PortfolioSnapshot(
                portfolioId,
                userId,
                name,
                broker,
                assets.stream().map(PortfolioFactory::toSnapshot).toList(),
                PortfolioStatus.OPEN,
                List.copyOf(contributions),
                // Nothing has been sold yet, whichever way the portfolio was opened (F6), and no
                // trade has been counted into it (F10).
                List.of(),
                Set.of(),
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
