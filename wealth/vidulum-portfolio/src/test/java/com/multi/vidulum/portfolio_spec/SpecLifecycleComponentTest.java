package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.Answer;
import com.multi.vidulum.portfolio_spec.domain.AnswerKind;
import com.multi.vidulum.portfolio_spec.domain.Difference;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.portfolio_spec.domain.IllegalSpecTransitionException;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.portfolio_spec.domain.SnapshotExpiredException;
import com.multi.vidulum.portfolio_spec.domain.SnapshotPosition;
import com.multi.vidulum.portfolio_spec.domain.SpecStatus;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a synchronisation ends when it is not applied (task D10).
 *
 * <p>Three endings had no expression at all. A specification could only be confirmed, so an
 * abandoned onboarding sat in {@code AWAITING_ANSWER} for good and anything counting pending work
 * counted ghosts; an anchor could age out without anyone being told until confirmation refused it,
 * by which point the owner had answered everything; and a failed write left a state nothing could
 * interpret.
 *
 * <p>The rule that shapes all of it: <b>the snapshot expires, the answers do not.</b> An answer is
 * anchored to a batch, so a batch that survives a recomputation keeps its decision.
 */
class SpecLifecycleComponentTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final Ticker BTC = Ticker.of("BTC");

    private static ExchangeSnapshot snapshot(ZonedDateTime takenAt, double total, double traded) {
        return new ExchangeSnapshot(Broker.of("OKX"), takenAt, List.of(
                new SnapshotPosition(BTC, Quantity.of(total), Quantity.of(traded), null,
                        Price.of(50_000, "USD"))));
    }

    private static PortfolioSpec specAt(ZonedDateTime takenAt, double total, double traded) {
        return PortfolioSpec.from(PortfolioSpecId.of("spec-1"), ALICE, "conn-1",
                Currency.of("EUR"), null, List.of(), snapshot(takenAt, total, traded), takenAt);
    }

    private static void answerUnknown(PortfolioSpec spec, double quantity, ZonedDateTime when) {
        spec.answer(BTC, SubName.transferredIn(), Quantity.of(quantity),
                new Answer(AnswerKind.COST_UNKNOWN, null), when);
    }

    private static Difference of(PortfolioSpec spec, SubName subName) {
        return spec.getDifferences().stream()
                .filter(difference -> difference.subName().equals(subName))
                .findFirst().orElseThrow();
    }

    // --- the anchor ages, and says so early ----------------------------------------------------

    @Test
    void shouldRefuseAnAnswerAnchoredToAReadingThatAgedOut() {
        PortfolioSpec spec = specAt(NOW, 1.3, 0.3);

        assertThatThrownBy(() -> answerUnknown(spec, 1.0, NOW.plusMinutes(16)))
                .isInstanceOf(SnapshotExpiredException.class)
                .hasMessageContaining("read the account again");

        assertThat(spec.getStatus())
                .as("an interface should now offer a re-read, not the same form")
                .isEqualTo(SpecStatus.STALE);
    }

    @Test
    void shouldStillTakeAnAnswerWhileTheReadingIsCurrent() {
        PortfolioSpec spec = specAt(NOW, 1.3, 0.3);

        answerUnknown(spec, 1.0, NOW.plusMinutes(14));

        assertThat(spec.getStatus()).isEqualTo(SpecStatus.CONFIRMED);
    }

    // --- the snapshot expires, the answers do not -----------------------------------------------

    /**
     * The batch the owner answered about is still there in the newer reading, so the decision
     * stands. Asking again would be asking somebody to repeat themselves because we looked twice.
     */
    @Test
    void shouldKeepAnAnswerWhoseBatchSurvivedTheRecomputation() {
        PortfolioSpec spec = specAt(NOW, 1.3, 0.3);
        answerUnknown(spec, 1.0, NOW);
        assertThat(spec.getStatus()).isEqualTo(SpecStatus.CONFIRMED);

        // Read again 20 minutes later: same holding, so the same two batches come back.
        spec.markStale(snapshot(NOW.plusMinutes(20), 1.3, 0.3), List.of(), NOW.plusMinutes(20));

        assertThat(of(spec, SubName.transferredIn()).isAnswered())
                .as("the batch did not move, so neither did the decision about it")
                .isTrue();
        assertThat(spec.needsAnswers()).isFalse();
        assertThat(spec.getStatus()).isEqualTo(SpecStatus.CONFIRMED);
    }

    /** A batch that changed size is a different batch, and has to be asked about again. */
    @Test
    void shouldAskAgainAboutABatchThatChanged() {
        PortfolioSpec spec = specAt(NOW, 1.3, 0.3);
        answerUnknown(spec, 1.0, NOW);

        spec.markStale(snapshot(NOW.plusMinutes(20), 1.8, 0.3), List.of(), NOW.plusMinutes(20));

        assertThat(of(spec, SubName.transferredIn()).quantity()).isEqualTo(Quantity.of(1.5));
        assertThat(spec.needsAnswers())
                .as("1.5 arrived from outside, and nobody has said anything about 1.5")
                .isTrue();
        assertThat(spec.getStatus()).isEqualTo(SpecStatus.AWAITING_ANSWER);
    }

    /**
     * Recomputing also replaces the anchor. It used to keep the old one, which made the whole
     * exercise pointless: the clock kept running against the reading that had just been replaced.
     */
    @Test
    void shouldStartTheClockAgainFromTheNewerReading() {
        PortfolioSpec spec = specAt(NOW, 1.3, 0.3);
        assertThat(spec.isSnapshotExpired(NOW.plusMinutes(20))).isTrue();

        spec.markStale(snapshot(NOW.plusMinutes(20), 1.3, 0.3), List.of(), NOW.plusMinutes(20));

        assertThat(spec.isSnapshotExpired(NOW.plusMinutes(25))).isFalse();
        assertThat(spec.getSnapshot().takenAt()).isEqualTo(NOW.plusMinutes(20));
    }

    // --- the two other endings -------------------------------------------------------------------

    @Test
    void shouldLetTheOwnerWalkAwayForGood() {
        PortfolioSpec spec = specAt(NOW, 1.3, 0.3);

        spec.cancel();

        assertThat(spec.getStatus()).isEqualTo(SpecStatus.CANCELLED);
        assertThatThrownBy(() -> answerUnknown(spec, 1.0, NOW))
                .as("terminal means terminal")
                .isInstanceOf(IllegalSpecTransitionException.class);
    }

    /**
     * A failed write is recoverable, and that is the difference worth recording: every decision in
     * the specification is still valid, so "try again" is a different instruction from "start over".
     */
    @Test
    void shouldKeepTheDecisionsWhenWritingThePortfolioFailed() {
        PortfolioSpec spec = specAt(NOW, 1.3, 0.3);
        answerUnknown(spec, 1.0, NOW);

        spec.markFailed();

        assertThat(spec.getStatus()).isEqualTo(SpecStatus.FAILED);
        assertThat(of(spec, SubName.transferredIn()).isAnswered()).isTrue();
        spec.markApplied(PortfolioId.of("portfolio-1"), NOW.plusMinutes(1));
        assertThat(spec.getStatus())
                .as("FAILED is not a dead end — the same specification can be applied next time")
                .isEqualTo(SpecStatus.APPLIED);
    }
}
