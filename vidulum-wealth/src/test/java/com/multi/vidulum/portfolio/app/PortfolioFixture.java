package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.portfolio.domain.portfolio.ContributionId;
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
import com.multi.vidulum.portfolio.domain.portfolio.Contribution;
import java.time.ZonedDateTime;
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
    private final List<Contribution> contributions = new ArrayList<>();
    private PortfolioStatus status = PortfolioStatus.OPEN;

    private static final ZonedDateTime CONTRIBUTED_AT = ZonedDateTime.parse("2022-01-01T00:00:00Z");

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
        return this;
    }

    public PortfolioFixture status(PortfolioStatus status) {
        this.status = status;
        return this;
    }

    /** A position acquired through a trade, so its cost is known. */
    public PortfolioFixture with(String ticker, Quantity quantity, CostBasis costBasis) {
        return at(SubName.traded(), ticker, quantity, costBasis);
    }

    /**
     * A position transferred in from outside: we hold it, and we have no idea what it cost.
     * Impossible to reach through deposits or trades, which is most of why this fixture exists.
     */
    public PortfolioFixture withUnknownCost(String ticker, Quantity quantity) {
        return at(SubName.transferredIn(), ticker, quantity, null);
    }

    /** Cash — never split by origin, always at par. */
    public PortfolioFixture withCash(String currency, Quantity quantity) {
        return at(SubName.none(), currency, quantity, CostBasis.atPar(quantity, currency));
    }

    /** Full control, for the cases the named helpers do not cover. */
    public PortfolioFixture at(SubName subName, String ticker, Quantity quantity, CostBasis costBasis) {
        assets.add(new PortfolioSnapshot.AssetSnapshot(
                Ticker.of(ticker), subName, costBasis,
                quantity, Quantity.zero(quantity.getUnit()), quantity, List.of()));
        return this;
    }

    /**
     * Something the owner put in, whose value at the time is known — a cash deposit, or a holding
     * transferred in on a day we could price.
     */
    public PortfolioFixture contributed(Money money) {
        contributions.add(Contribution.paidIn(nextId(), money, CONTRIBUTED_AT));
        return this;
    }

    /**
     * Something the owner put in whose value at the time nobody knows — a coin transferred in from
     * an exchange we cannot price backwards. Unreachable through deposits, and the whole reason the
     * ledger reports coverage rather than a bare total.
     */
    public PortfolioFixture contributedOfUnknownValue(Money money) {
        contributions.add(new Contribution(nextId(), CONTRIBUTED_AT,
                Contribution.Direction.IN, money, null, null));
        return this;
    }

    /** Money the owner took back out — lowers what the portfolio is measured against. */
    public PortfolioFixture withdrawn(Money money) {
        contributions.add(Contribution.takenOut(nextId(), money, CONTRIBUTED_AT));
        return this;
    }

    private ContributionId nextId() {
        return ContributionId.of("contribution-" + (contributions.size() + 1));
    }

    public Portfolio build() {
        return Portfolio.from(new PortfolioSnapshot(
                portfolioId, userId, name, broker, List.copyOf(assets),
                status, List.copyOf(contributions), allowedDepositCurrency));
    }
}
