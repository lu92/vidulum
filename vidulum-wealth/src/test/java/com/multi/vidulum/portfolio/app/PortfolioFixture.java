package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.PortfolioStatus;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.snapshots.PortfolioSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link Portfolio} in whatever state a test needs, rather than driving it there
 * through deposits and trades.
 *
 * <p>Without this, stating "a position of 100 units whose cost we do not know" takes a deposit,
 * an order and an executed trade — and even then some states are unreachable, because no
 * production path yet produces an asset without a cost. The fixture goes through
 * {@code Portfolio.from(PortfolioSnapshot)}, the same entry the repository uses when it rehydrates
 * a document, so nothing here bypasses the mapping the production code relies on.
 */
public final class PortfolioFixture {

    private final List<PortfolioSnapshot.AssetSnapshot> assets = new ArrayList<>();
    private PortfolioId portfolioId = PortfolioId.of("portfolio-1");
    private UserId userId = UserId.of("U10000001");
    private String name = "XYZ";
    private Broker broker = Broker.of("BROKER");
    private Currency allowedDepositCurrency = Currency.of("USD");
    private Money investedBalance = Money.zero("USD");
    private PortfolioStatus status = PortfolioStatus.OPEN;

    public static PortfolioFixture portfolio() {
        return new PortfolioFixture();
    }

    public PortfolioFixture id(String id) {
        this.portfolioId = PortfolioId.of(id);
        return this;
    }

    public PortfolioFixture ownedBy(UserId userId) {
        this.userId = userId;
        return this;
    }

    public PortfolioFixture named(String name) {
        this.name = name;
        return this;
    }

    public PortfolioFixture at(Broker broker) {
        this.broker = broker;
        return this;
    }

    public PortfolioFixture denominatedIn(String currency) {
        this.allowedDepositCurrency = Currency.of(currency);
        this.investedBalance = Money.zero(currency);
        return this;
    }

    public PortfolioFixture invested(Money investedBalance) {
        this.investedBalance = investedBalance;
        return this;
    }

    public PortfolioFixture status(PortfolioStatus status) {
        this.status = status;
        return this;
    }

    /** A position whose cost we know. */
    public PortfolioFixture with(String ticker, Quantity quantity, CostBasis costBasis) {
        assets.add(new PortfolioSnapshot.AssetSnapshot(
                Ticker.of(ticker), SubName.none(), costBasis,
                quantity, Quantity.zero(quantity.getUnit()), quantity, List.of()));
        return this;
    }

    /**
     * A position transferred in from outside: we hold it, and we have no idea what it cost.
     * Impossible to reach through deposits or trades, which is most of why this fixture exists.
     */
    public PortfolioFixture withUnknownCost(String ticker, Quantity quantity) {
        return with(ticker, quantity, null);
    }

    public Portfolio build() {
        return Portfolio.from(new PortfolioSnapshot(
                portfolioId, userId, name, broker, List.copyOf(assets),
                status, investedBalance, allowedDepositCurrency));
    }
}
