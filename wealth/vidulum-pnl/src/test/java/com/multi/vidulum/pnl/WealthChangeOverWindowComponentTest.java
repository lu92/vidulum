package com.multi.vidulum.pnl;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.pnl.app.queries.GetWealthChangeQuery;
import com.multi.vidulum.pnl.app.queries.GetWealthChangeQueryHandler;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.pnl.domain.PnlId;
import com.multi.vidulum.pnl.domain.PnlPortfolioStatement;
import com.multi.vidulum.pnl.domain.PnlStatement;
import com.multi.vidulum.pnl.domain.ValuationBasis;
import com.multi.vidulum.pnl.domain.WealthChangeOverWindow;
import com.multi.vidulum.portfolio.api.view.AggregatedPortfolioView;
import com.multi.vidulum.portfolio.api.PortfolioModuleApi;
import com.multi.vidulum.portfolio.api.view.PortfolioView;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.multi.vidulum.pnl.domain.WealthChangeOverWindow.WealthChangeStatus.COMPUTED;
import static com.multi.vidulum.pnl.domain.WealthChangeOverWindow.WealthChangeStatus.CONTRIBUTIONS_UNKNOWN;
import static com.multi.vidulum.pnl.domain.WealthChangeOverWindow.WealthChangeStatus.CURRENCY_CHANGED;
import static com.multi.vidulum.pnl.domain.WealthChangeOverWindow.WealthChangeStatus.NOT_WATCHING_YET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * "And how much did I make this month?" (task C14).
 *
 * <p>C5 answers the same question since the portfolio began, because that was the only starting
 * point available. This one needs two dated ends, and both already exist: today's from the
 * portfolio, the earlier one from a recorded valuation (F9).
 *
 * <p>The half that makes it honest is the subtraction: deposits made inside the window are netted
 * out, or money the owner paid in reads as money they made.
 */
class WealthChangeOverWindowComponentTest {

    private static final UserId ALICE = UserId.of("U10000001");
    private static final PortfolioId PORTFOLIO = PortfolioId.of("portfolio-1");
    private static final ZonedDateTime FIRST = ZonedDateTime.parse("2022-01-01T01:00:00Z");
    private static final ZonedDateTime MONTH_START = ZonedDateTime.parse("2022-02-01T00:00:00Z");

    /**
     * The daily record taken before the window opens. Valuations land at 01:00 and a month starts
     * at midnight, so the record that answers "since 1 February" is the one from the night before —
     * never a later one, which would answer with information the date did not have (F9).
     */
    private static final ZonedDateTime RECORDED = ZonedDateTime.parse("2022-01-31T01:00:00Z");

    private PortfolioView today = summary(120_000, 100_000d);
    private PnlHistory history = historyWith();

    private final DomainPnlRepository repository = new DomainPnlRepository() {
        @Override
        public Optional<PnlHistory> findByUser(UserId userId) {
            return ALICE.equals(userId) ? Optional.ofNullable(history) : Optional.empty();
        }

        @Override
        public List<UserId> everyOwner() {
            return List.of(ALICE);
        }

        @Override
        public Optional<PnlHistory> findById(PnlId pnlId) {
            return Optional.ofNullable(history);
        }

        @Override
        public PnlHistory save(PnlHistory aggregate) {
            history = aggregate;
            return aggregate;
        }
    };

    /** Only the read this measure needs; the rest of the interface is not this test's subject. */
    private final PortfolioModuleApi portfolios = new PortfolioModuleApi() {
        @Override
        public PortfolioView get(PortfolioId portfolioId) {
            return today;
        }

        @Override
        public PortfolioView get(PortfolioId portfolioId, com.multi.vidulum.common.Currency valuedIn) {
            // Answers what the test set up regardless of the currency asked for, which is how a
            // mismatch between the two ends can be staged at all - the real module would convert.
            return today;
        }

        @Override
        public AggregatedPortfolioView aggregate(
                UserId owner, com.multi.vidulum.common.Currency valuedIn) {
            throw new UnsupportedOperationException("not needed here");
        }

        @Override
        public PortfolioId open(String name, UserId owner,
                                com.multi.vidulum.common.Broker broker,
                                com.multi.vidulum.common.Currency allowedDepositCurrency) {
            throw new UnsupportedOperationException("not needed here");
        }

        @Override
        public void lock(PortfolioId portfolioId, com.multi.vidulum.common.Ticker ticker,
                         com.multi.vidulum.common.OrderId orderId,
                         com.multi.vidulum.common.Quantity quantity) {
            throw new UnsupportedOperationException("not needed here");
        }

        @Override
        public void unlock(PortfolioId portfolioId, com.multi.vidulum.common.Ticker ticker,
                           com.multi.vidulum.common.OrderId orderId,
                           com.multi.vidulum.common.Quantity quantity) {
            throw new UnsupportedOperationException("not needed here");
        }
    };

    private final GetWealthChangeQueryHandler handler =
            new GetWealthChangeQueryHandler(repository, portfolios);

    private static PortfolioView summary(double value, Double contributed) {
        return summaryIn("EUR", value, contributed);
    }

    private static PortfolioView summaryIn(
            String currency, double value, Double contributed) {
        return new PortfolioView(
                PORTFOLIO, ALICE, "Alice's", com.multi.vidulum.common.Broker.of("PM"),
                Money.of(value, currency),
                contributed == null ? null : Money.of(contributed, currency),
                null, null, java.util.List.of());
    }

    private PnlHistory historyWith(PnlStatement... statements) {
        return PnlHistory.builder()
                .pnlId(PnlId.of("pnl-1")).userId(ALICE)
                .pnlStatements(new ArrayList<>(List.of(statements)))
                .build();
    }

    private static PnlStatement recorded(ZonedDateTime when, double value, Double contributed) {
        return recordedIn("EUR", when, value, contributed);
    }

    private static PnlStatement recordedIn(
            String currency, ZonedDateTime when, double value, Double contributed) {
        return PnlStatement.builder()
                .dateTime(when)
                .basis(ValuationBasis.pivot(Currency.of(currency)))
                .currentValue(Money.of(value, currency))
                .pnlPortfolioStatements(List.of(PnlPortfolioStatement.builder()
                        .portfolioId(PORTFOLIO)
                        .currentValue(Money.of(value, currency))
                        .netContributions(contributed == null ? null : Money.of(contributed, currency))
                        .executedTrades(List.of())
                        .build()))
                .build();
    }

    private WealthChangeOverWindow changeSince(ZonedDateTime since) {
        return handler.query(new GetWealthChangeQuery(ALICE, PORTFOLIO, since));
    }

    // --- the measure ------------------------------------------------------------------------------

    @Test
    void shouldReportWhatWasMadeInsideTheWindow() {
        history = historyWith(recorded(RECORDED, 100_000, 100_000d));
        today = summary(120_000, 100_000d);

        WealthChangeOverWindow change = changeSince(MONTH_START);

        assertThat(change.status()).isEqualTo(COMPUTED);
        assertThat(change.change()).isEqualTo(Money.of(20_000, "EUR"));
        assertThat(change.pct())
                .as("a fifth of what the portfolio was worth when the window opened")
                .isCloseTo(0.2, within(1e-9));
    }

    /**
     * The half that makes it honest. Value rose by 25 000, but 5 000 of that the owner paid in —
     * and without netting it out a transfer reads as profit, which is the mistake C5 exists to
     * prevent and this measure inherits.
     */
    @Test
    void shouldNotCountADepositMadeInsideTheWindowAsGrowth() {
        history = historyWith(recorded(RECORDED, 100_000, 100_000d));
        today = summary(125_000, 105_000d);

        assertThat(changeSince(MONTH_START).change())
                .as("25 000 more, of which 5 000 is theirs")
                .isEqualTo(Money.of(20_000, "EUR"));
    }

    @Test
    void shouldReportALossTheSameWay() {
        history = historyWith(recorded(RECORDED, 100_000, 100_000d));
        today = summary(88_000, 100_000d);

        assertThat(changeSince(MONTH_START).change()).isEqualTo(Money.of(-12_000, "EUR"));
    }

    /** It says which day actually answered — a daily record rarely lands on the asked instant. */
    @Test
    void shouldSayWhichRecordAnsweredTheQuestion() {
        history = historyWith(recorded(RECORDED, 100_000, 100_000d));

        assertThat(changeSince(MONTH_START).measuredFrom())
                .as("asked about midnight, answered by the last record before it")
                .isEqualTo(RECORDED);
    }

    // --- the two silences --------------------------------------------------------------------------

    @Test
    void shouldWithholdWhenTheWindowStartsBeforeWeWereWatching() {
        history = historyWith(recorded(RECORDED, 100_000, 100_000d));

        WealthChangeOverWindow change = changeSince(FIRST);

        assertThat(change.status()).isEqualTo(NOT_WATCHING_YET);
        assertThat(change.change())
                .as("the alternative is valuing old holdings at today's prices")
                .isNull();
    }

    @Test
    void shouldWithholdWhenEitherEndCannotSayWhatWasPutIn() {
        history = historyWith(recorded(RECORDED, 100_000, null));
        today = summary(120_000, 100_000d);

        assertThat(changeSince(MONTH_START).status()).isEqualTo(CONTRIBUTIONS_UNKNOWN);

        history = historyWith(recorded(RECORDED, 100_000, 100_000d));
        today = summary(120_000, null);

        assertThat(changeSince(MONTH_START).status()).isEqualTo(CONTRIBUTIONS_UNKNOWN);
    }

    /**
     * A third silence, added by C17: the two ends are not in the same currency.
     *
     * <p>It has to be a refusal, not a conversion. Restating the older end at today's rate folds
     * the currency's movement into the portfolio's and reports them as one number — the confusion
     * C17 exists to end — and restating at the older rate misstates today. It also cannot be left
     * to subtract quietly: {@code Money.minus} keeps the left-hand currency and ignores the right,
     * so an unlike pair would answer in whichever came first, the defect F6 paid for once already.
     */
    @Test
    void shouldRefuseToCompareTwoEndsInDifferentCurrencies() {
        history = historyWith(recordedIn("USD", RECORDED, 100_000, 100_000d));
        today = summaryIn("PLN", 480_000, 400_000d);

        WealthChangeOverWindow change = changeSince(MONTH_START);

        assertThat(change.status()).isEqualTo(CURRENCY_CHANGED);
        assertThat(change.change())
                .as("no figure can span a change of currency")
                .isNull();
        assertThat(change.pct()).isNull();
    }

    /** Same currency on both ends is the ordinary case, and it still computes. */
    @Test
    void shouldCompareTwoEndsInTheSameCurrency() {
        history = historyWith(recordedIn("PLN", RECORDED, 400_000, 400_000d));
        today = summaryIn("PLN", 480_000, 400_000d);

        WealthChangeOverWindow change = changeSince(MONTH_START);

        assertThat(change.status()).isEqualTo(COMPUTED);
        assertThat(change.change()).isEqualTo(Money.of(80_000, "PLN"));
    }

    /**
     * An end that does not know what was put in is asked about first. It has no currency to
     * disagree about, and answering CURRENCY_CHANGED there would name the wrong reason — found by
     * this very test when the two checks were the other way round.
     */
    @Test
    void shouldNameTheMissingContributionRatherThanTheCurrencyWhenBothCouldApply() {
        history = historyWith(recordedIn("USD", RECORDED, 100_000, null));
        today = summaryIn("PLN", 480_000, 400_000d);

        assertThat(changeSince(MONTH_START).status()).isEqualTo(CONTRIBUTIONS_UNKNOWN);
    }

    /** A portfolio worth nothing at the start has no base for a percentage — the figure still stands. */
    @Test
    void shouldStateTheChangeWithoutAPercentageWhenThereWasNothingToGrowFrom() {
        history = historyWith(recorded(RECORDED, 0, 0d));
        today = summary(5_000, 5_000d);

        WealthChangeOverWindow change = changeSince(MONTH_START);

        assertThat(change.change())
                .as("all of it was paid in, so nothing was made")
                .isEqualTo(Money.of(0, "EUR"));
        assertThat(change.pct()).isNull();
    }
}
