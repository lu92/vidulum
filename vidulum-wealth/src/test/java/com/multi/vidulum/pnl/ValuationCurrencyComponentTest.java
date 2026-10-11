package com.multi.vidulum.pnl;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Range;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.pnl.app.commands.MakePnlSnapshotCommand;
import com.multi.vidulum.pnl.app.commands.MakePnlSnapshotCommandHandler;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.pnl.domain.PnlId;
import com.multi.vidulum.pnl.domain.PnlStatement;
import com.multi.vidulum.pnl.domain.ValuationBasis;
import com.multi.vidulum.pnl.domain.ValuationRates;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.TradingRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioRestClient;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.domain.fx.RateSourceId;
import com.multi.vidulum.trading.app.TradingDto;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a recorded valuation is denominated in, and who decides (task C17).
 *
 * <p>The currency used to be {@code USD}, written into a client implementation two layers below
 * anyone asking for a snapshot. That was not a decision while the backend could not value a
 * portfolio in anything else; after B4 it is one, and it is the <b>only</b> decision here that can
 * never be revisited — what a currency was worth last Tuesday cannot be looked up on Friday, so a
 * statement recorded without it is incomplete for good.
 *
 * <p>Worse, the choice was inconsistent with itself: the totals were dollars while each portfolio
 * statement beside them was read in whatever currency that portfolio settled in, so one statement
 * mixed currencies and nothing in it said so.
 */
class ValuationCurrencyComponentTest {

    private static final UserId ALICE = UserId.of("U10000001");
    private static final PortfolioId EUROS = PortfolioId.of("portfolio-eur");
    private static final PortfolioId ZLOTYS = PortfolioId.of("portfolio-pln");
    private static final RateSourceId NBP = RateSourceId.of("NBP");
    private static final ZonedDateTime FRIDAY = ZonedDateTime.parse("2026-09-25T00:00+02:00[Europe/Warsaw]");
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);

    private PnlHistory history = PnlHistory.builder()
            .pnlId(PnlId.of("pnl-1")).userId(ALICE)
            .pnlStatements(new ArrayList<>())
            .build();

    private final DomainPnlRepository repository = new DomainPnlRepository() {
        @Override
        public Optional<PnlHistory> findByUser(UserId userId) {
            return ALICE.equals(userId) ? Optional.of(history) : Optional.empty();
        }

        @Override
        public List<UserId> everyOwner() {
            return List.of(ALICE);
        }

        @Override
        public Optional<PnlHistory> findById(PnlId pnlId) {
            return Optional.of(history);
        }

        @Override
        public PnlHistory save(PnlHistory aggregate) {
            history = aggregate;
            return aggregate;
        }
    };

    /** Remembers what it was asked for, which is the whole subject here. */
    private final List<String> askedFor = new ArrayList<>();

    private final PortfolioRestClient portfolios = new PortfolioRestClient() {
        @Override
        public PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(
                UserId userId, Currency denominatedIn) {
            askedFor.add("aggregate:" + denominatedIn.getId());
            return PortfolioDto.AggregatedPortfolioSummaryJson.builder()
                    .userId(userId.getId())
                    .portfolioIds(List.of(EUROS.getId(), ZLOTYS.getId()))
                    .currentValue(Money.of(50_000, denominatedIn.getId()))
                    .netContributions(Money.of(40_000, denominatedIn.getId()))
                    .build();
        }

        @Override
        public PortfolioDto.PortfolioSummaryJson getPortfolio(PortfolioId portfolioId, Currency denominatedIn) {
            askedFor.add(portfolioId.getId() + ":" + denominatedIn.getId());
            return PortfolioDto.PortfolioSummaryJson.builder()
                    .portfolioId(portfolioId.getId())
                    .currentValue(Money.of(25_000, denominatedIn.getId()))
                    .netContributions(Money.of(20_000, denominatedIn.getId()))
                    .build();
        }

        @Override
        public PortfolioDto.PortfolioSummaryJson getPortfolio(PortfolioId portfolioId) {
            throw new AssertionError("a recorded statement must never read a portfolio "
                    + "in its own currency - that is how one statement came to mix them");
        }

        @Override
        public PortfolioId createPortfolio(String name, UserId userId,
                                           com.multi.vidulum.common.Broker broker, Currency currency) {
            throw new UnsupportedOperationException("not needed here");
        }

        @Override
        public void lockAsset(PortfolioId portfolioId, Ticker ticker,
                              com.multi.vidulum.common.OrderId orderId,
                              com.multi.vidulum.common.Quantity quantity) {
            throw new UnsupportedOperationException("not needed here");
        }

        @Override
        public void unlockAsset(PortfolioId portfolioId, Ticker ticker,
                                com.multi.vidulum.common.OrderId orderId,
                                com.multi.vidulum.common.Quantity quantity) {
            throw new UnsupportedOperationException("not needed here");
        }
    };

    private final TradingRestClient trades = new TradingRestClient() {
        @Override
        public List<TradingDto.OrderSummaryJson> getOpenedOrders(PortfolioId portfolioId) {
            return List.of();
        }

        @Override
        public List<TradingDto.TradeSummaryJson> getTradesInDateRange(
                UserId userId, Range<ZonedDateTime> dateTimeRange) {
            return List.of();
        }
    };

    private final Map<String, ValuationBasis> known = new HashMap<>();
    private final ValuationRates valuationRates = currency -> Optional.ofNullable(known.get(currency.getId()));

    private final MakePnlSnapshotCommandHandler handler =
            new MakePnlSnapshotCommandHandler(portfolios, trades, repository, valuationRates, clock);

    private PnlStatement record(String currency) {
        handler.handle(MakePnlSnapshotCommand.builder()
                .userId(ALICE)
                .dateTimeRange(Range.of(NOW.minusDays(1), NOW))
                .valuationCurrency(Currency.of(currency))
                .build());
        return history.getPnlStatements().getLast();
    }

    // --- who decides ------------------------------------------------------------------------------

    /**
     * The command has to say. A default would put the choice back where C17 found it — made
     * silently, by whoever did not state one — and the resulting statement would look no different
     * from one that was chosen on purpose.
     */
    @Test
    void shouldRefuseToRecordAValuationThatDoesNotSayWhatCurrencyItIsIn() {
        assertThatThrownBy(() -> MakePnlSnapshotCommand.builder()
                .userId(ALICE)
                .dateTimeRange(Range.of(NOW.minusDays(1), NOW))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("what currency");
    }

    @Test
    void shouldValueTheHoldingsInTheCurrencyTheCommandAsksFor() {
        known.put("PLN", new ValuationBasis(
                Currency.of("PLN"), FxRate.of("PLN", "USD", 0.26, FRIDAY, NBP)));

        PnlStatement statement = record("PLN");

        assertThat(statement.getCurrentValue()).isEqualTo(Money.of(50_000, "PLN"));
        assertThat(statement.getNetContributions()).isEqualTo(Money.of(40_000, "PLN"));
        assertThat(askedFor).contains("aggregate:PLN");
    }

    /**
     * Every portfolio in the statement in the same currency as the statement. They used to be read
     * in whatever each portfolio settled in, beside a total in dollars — parts and whole disagreed
     * and the payload never said which was which.
     */
    @Test
    void shouldValueEveryPortfolioInTheStatementsOwnCurrency() {
        known.put("PLN", ValuationBasis.pivot(Currency.of("PLN")));

        PnlStatement statement = record("PLN");

        assertThat(askedFor).contains("portfolio-eur:PLN", "portfolio-pln:PLN");
        assertThat(statement.getPnlPortfolioStatements())
                .allSatisfy(portfolio -> assertThat(portfolio.getCurrentValue().getCurrency())
                        .isEqualTo("PLN"));
    }

    // --- what is written beside the figures --------------------------------------------------------

    /**
     * The rate is stored with the statement, not looked up when somebody reads it. This is the
     * only part of the entry that cannot be reconstructed later — and with it stored, the harder
     * question of which currency a history ought to be kept in stays answerable afterwards.
     */
    @Test
    void shouldRecordWhatTheCurrencyWasWorthAtTheTime() {
        known.put("PLN", new ValuationBasis(
                Currency.of("PLN"), FxRate.of("PLN", "USD", 0.26, FRIDAY, NBP)));

        PnlStatement statement = record("PLN");

        assertThat(statement.getBasis().currency()).isEqualTo(Currency.of("PLN"));
        assertThat(statement.getBasis().rate().rate().getAmount().doubleValue()).isEqualTo(0.26);
        assertThat(statement.getBasis().rate().asOf())
                .as("the day the fixing applies to, so a stale rate stays visibly stale")
                .isEqualTo(FRIDAY);
        assertThat(statement.getBasis().rate().source()).isEqualTo(NBP);
    }

    @Test
    void shouldRecordThePivotWithoutARate() {
        known.put("USD", ValuationBasis.pivot(Currency.of("USD")));

        PnlStatement statement = record("USD");

        assertThat(statement.getBasis().currency()).isEqualTo(Currency.of("USD"));
        assertThat(statement.getBasis().knownRate())
                .as("one dollar in dollars is arithmetic, with no moment and no publisher")
                .isEmpty();
    }

    /**
     * No rate held must not cost the day its record. The gap a missing valuation leaves is
     * permanent, while an entry that admits it does not know the rate is still the valuation —
     * the same trade-off the nightly job makes when one owner's quote is missing.
     */
    @Test
    void shouldStillRecordTheValuationWhenNoRateIsHeld() {
        PnlStatement statement = record("PLN");

        assertThat(statement.getCurrentValue()).isEqualTo(Money.of(50_000, "PLN"));
        assertThat(statement.getBasis().currency()).isEqualTo(Currency.of("PLN"));
        assertThat(statement.getBasis().knownRate()).isEmpty();
    }

    /** And it survives the round trip through the snapshot the repository stores. */
    @Test
    void shouldCarryTheBasisThroughPersistence() {
        known.put("PLN", new ValuationBasis(
                Currency.of("PLN"), FxRate.of("PLN", "USD", 0.26, FRIDAY, NBP)));
        record("PLN");

        PnlHistory reloaded = PnlHistory.from(history.getSnapshot());

        assertThat(reloaded.getPnlStatements().getLast().getBasis())
                .usingRecursiveComparison()
                .isEqualTo(history.getPnlStatements().getLast().getBasis());
    }
}
