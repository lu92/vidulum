package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.PortfolioFixture;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio_spec.domain.Difference;
import com.multi.vidulum.portfolio_spec.domain.DifferenceDirection;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.portfolio_spec.domain.IllegalSpecTransitionException;
import com.multi.vidulum.portfolio_spec.domain.NothingToSynchroniseException;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.portfolio_spec.domain.QuestionKind;
import com.multi.vidulum.portfolio_spec.domain.SnapshotPosition;
import com.multi.vidulum.portfolio_spec.domain.SpecStatus;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The difference engine and the specification around it (task D1), at component level.
 *
 * <p>The claim being tested is that <b>onboarding is not a special case</b>: it is
 * {@code snapshot - known state} with an empty known state. If that holds, the second
 * synchronisation stops re-asking about the whole portfolio — which is the entire reason this
 * machinery exists instead of a one-off import.
 */
class PortfolioSpecEngineTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final Broker OKX = Broker.of("OKX");
    private static final Ticker BTC = Ticker.of("BTC");
    private static final String CONNECTION = "conn-1";

    private static ExchangeSnapshot snapshot(ZonedDateTime takenAt, SnapshotPosition... positions) {
        return new ExchangeSnapshot(OKX, takenAt, List.of(positions));
    }

    private static SnapshotPosition btc(double total, double traded, Double price) {
        return new SnapshotPosition(BTC, Quantity.of(total), Quantity.of(traded),
                price == null ? null : Price.of(price, "USD"));
    }

    private static PortfolioSpec specFrom(List<Asset> knownState, ExchangeSnapshot snapshot) {
        return PortfolioSpec.from(PortfolioSpecId.of("spec-1"), ALICE, CONNECTION,
                knownState, snapshot, NOW);
    }

    /** Matches on ticker as well as position: two tickers can share a subName. */
    private static Difference of(PortfolioSpec spec, Ticker ticker, SubName subName) {
        return spec.getDifferences().stream()
                .filter(difference -> difference.ticker().equals(ticker)
                        && difference.subName().equals(subName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no difference for " + ticker.getId() + "/" + subName.getName()
                                + " in " + spec.getDifferences()));
    }

    private static Difference of(PortfolioSpec spec, SubName subName) {
        return of(spec, BTC, subName);
    }

    /**
     * The whole point of the split from C2: the exchange reports a total and a traded part, so
     * one snapshot line becomes two positions - one priced, one not.
     */
    @Test
    void shouldSplitOneSnapshotLineIntoAPricedAndAnUnpricedPosition() {
        PortfolioSpec spec = specFrom(List.of(), snapshot(NOW, btc(1.3, 0.3, 50_000.0)));

        assertThat(spec.getDifferences()).hasSize(2);

        Difference traded = of(spec, SubName.traded());
        assertThat(traded.quantity()).isEqualTo(Quantity.of(0.3));
        assertThat(traded.needsAnswer()).isFalse();
        assertThat(traded.resolvedCost())
                .isEqualTo(CostBasis.of(Quantity.of(0.3), Price.of(50_000, "USD"),
                        Provenance.EXCHANGE_REPORTED));

        Difference transferredIn = of(spec, SubName.transferredIn());
        assertThat(transferredIn.quantity().getQty()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(transferredIn.question()).isEqualTo(QuestionKind.ACQUISITION_COST);
        assertThat(transferredIn.resolvedCost())
                .as("the reported price covers the traded part only")
                .isNull();
    }

    @Test
    void shouldAwaitAnswersWhenAnythingNeedsAHuman() {
        PortfolioSpec spec = specFrom(List.of(), snapshot(NOW, btc(1.3, 0.3, 50_000.0)));

        assertThat(spec.getStatus()).isEqualTo(SpecStatus.AWAITING_ANSWER);
        assertThat(spec.openQuestions()).hasSize(1);
    }

    /**
     * The path most synchronisations take, and what D4 buys: everything priced, nobody asked.
     */
    @Test
    void shouldStayADraftWhenNothingNeedsAHuman() {
        PortfolioSpec spec = specFrom(List.of(), snapshot(NOW,
                btc(0.3, 0.3, 50_000.0),
                new SnapshotPosition(Ticker.of("EUR"), Quantity.of(5_000), Quantity.zero(), null)));

        assertThat(spec.getStatus()).isEqualTo(SpecStatus.DRAFT);
        assertThat(spec.needsAnswers()).isFalse();
        assertThat(of(spec, Ticker.of("EUR"), SubName.transferredIn()).resolvedCost().provenance())
                .isEqualTo(Provenance.ASSUMED_PAR);
    }

    /**
     * Onboarding is not a special case - so the second run, against a portfolio we already know,
     * must report only what moved.
     */
    @Test
    void shouldReportOnlyWhatMovedOnASecondSynchronisation() {
        List<Asset> known = PortfolioFixture.portfolio()
                .with("BTC", Quantity.of(0.3),
                        CostBasis.of(Quantity.of(0.3), Price.of(50_000, "USD"), Provenance.EXCHANGE_REPORTED))
                .withUnknownCost("BTC", Quantity.of(1))
                .build()
                .getAssets();

        PortfolioSpec spec = specFrom(known, snapshot(NOW, btc(1.8, 0.8, 55_000.0)));

        assertThat(spec.getDifferences()).hasSize(1);
        Difference traded = of(spec, SubName.traded());
        assertThat(traded.direction()).isEqualTo(DifferenceDirection.INCREASED);
        assertThat(traded.quantity().getQty()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(traded.needsAnswer())
                .as("an ordinary purchase the exchange priced must not become a question")
                .isFalse();
    }

    @Test
    void shouldAskWhenAPositionShrank() {
        List<Asset> known = PortfolioFixture.portfolio()
                .with("BTC", Quantity.of(0.5),
                        CostBasis.of(Quantity.of(0.5), Price.of(50_000, "USD"), Provenance.EXCHANGE_REPORTED))
                .build()
                .getAssets();

        PortfolioSpec spec = specFrom(known, snapshot(NOW, btc(0.2, 0.2, 50_000.0)));

        Difference traded = of(spec, SubName.traded());
        assertThat(traded.direction()).isEqualTo(DifferenceDirection.DECREASED);
        assertThat(traded.question()).isEqualTo(QuestionKind.DISPOSAL_REASON);
    }

    @Test
    void shouldReportAPositionThatDisappearedEntirely() {
        List<Asset> known = PortfolioFixture.portfolio()
                .withUnknownCost("BTC", Quantity.of(1))
                .build()
                .getAssets();

        PortfolioSpec spec = specFrom(known, snapshot(NOW,
                new SnapshotPosition(Ticker.of("EUR"), Quantity.of(10), Quantity.zero(), null)));

        assertThat(of(spec, SubName.transferredIn()).direction()).isEqualTo(DifferenceDirection.DECREASED);
    }

    /**
     * An empty spec would be a pending decision nobody can act on, left behind by every idle
     * synchronisation.
     */
    @Test
    void shouldRefuseToCreateASpecWhenNothingChanged() {
        List<Asset> known = PortfolioFixture.portfolio()
                .with("BTC", Quantity.of(0.3),
                        CostBasis.of(Quantity.of(0.3), Price.of(50_000, "USD"), Provenance.EXCHANGE_REPORTED))
                .build()
                .getAssets();

        assertThatThrownBy(() -> specFrom(known, snapshot(NOW, btc(0.3, 0.3, 50_000.0))))
                .isInstanceOf(NothingToSynchroniseException.class)
                .hasMessageContaining(ALICE.getId());
    }

    @Test
    void shouldRefuseASnapshotWhoseTradedPartExceedsWhatIsHeld() {
        assertThatThrownBy(() -> new SnapshotPosition(BTC, Quantity.of(0.5), Quantity.of(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void shouldRefuseASnapshotListingTheSameTickerTwice() {
        assertThatThrownBy(() -> snapshot(NOW, btc(1, 1, 50_000.0), btc(2, 2, 60_000.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    void shouldTreatTheSnapshotAsExpiredOnlyAfterTheTtl() {
        PortfolioSpec spec = specFrom(List.of(), snapshot(NOW, btc(1.3, 0.3, 50_000.0)));

        assertThat(spec.isSnapshotExpired(NOW.plusMinutes(14))).isFalse();
        assertThat(spec.isSnapshotExpired(NOW.plusMinutes(16))).isTrue();
    }

    @Test
    void shouldRecomputeAgainstAFresherSnapshot() {
        PortfolioSpec spec = specFrom(List.of(), snapshot(NOW, btc(1.3, 0.3, 50_000.0)));

        spec.markStale(snapshot(NOW.plusMinutes(20), btc(0.3, 0.3, 50_000.0)), List.of(),
                NOW.plusMinutes(20));

        assertThat(spec.getDifferences()).hasSize(1);
        assertThat(spec.needsAnswers())
                .as("the transferred-in units are gone, so its question goes with them")
                .isFalse();
        assertThat(spec.getStatus()).isEqualTo(SpecStatus.DRAFT);
        assertThat(spec.getLastRecomputedAt()).isEqualTo(NOW.plusMinutes(20));
    }

    /**
     * Without a terminal "gave up" state, specs sit in AWAITING_ANSWER forever and "thinking" is
     * indistinguishable from "gone".
     */
    @Test
    void shouldCancelAndRefuseAnythingAfterwards() {
        PortfolioSpec spec = specFrom(List.of(), snapshot(NOW, btc(1.3, 0.3, 50_000.0)));

        spec.cancel();

        assertThat(spec.getStatus()).isEqualTo(SpecStatus.CANCELLED);
        assertThatThrownBy(spec::cancel).isInstanceOf(IllegalSpecTransitionException.class);
        assertThatThrownBy(() -> spec.markStale(snapshot(NOW, btc(1, 1, 50_000.0)), List.of(), NOW))
                .isInstanceOf(IllegalSpecTransitionException.class);
    }
}
