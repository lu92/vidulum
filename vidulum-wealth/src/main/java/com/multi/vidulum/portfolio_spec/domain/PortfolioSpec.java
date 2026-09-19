package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What has to be decided before a portfolio can be written.
 *
 * <p>A portfolio is never built straight from a snapshot; it is built from a specification the
 * user confirmed. Onboarding is the case where the known state happens to be empty — the same
 * machinery serves every later synchronisation, which is what keeps the second one from asking
 * about the whole portfolio again.
 *
 * <p><b>It is not a second portfolio model.</b> It holds decisions, provenance and the snapshot
 * it is anchored to — and quantities only so an answer can be validated against that anchor
 * (§4.7). A spec that mirrored every field of every asset would be the read model we chose not
 * to build, under a different name.
 *
 * <p><b>The snapshot expires, the answers do not.</b> "The exchange said 3 BTC at 10:00" ages
 * with every trade on the account; "that ETH cost me 2 400 EUR" is true regardless. Going
 * {@link #markStale} recomputes the difference and keeps the answers whose batch still holds.
 */
@Getter
@Builder
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class PortfolioSpec {

    /** POC value. Long enough to answer a few questions, short enough that the anchor still means something. */
    public static final Duration SNAPSHOT_TTL = Duration.ofMinutes(15);

    private final PortfolioSpecId id;
    private final UserId userId;

    /** Which exchange account this describes. Empty until connections exist for every source. */
    private final String connectionId;

    /** The anchor: everything is validated against it, and it is what ages. */
    private final ExchangeSnapshot snapshot;

    private List<Difference> differences;
    private SpecStatus status;

    /** Set by {@code confirm} (D3). The reference points this way on purpose — see §4.8. */
    private PortfolioId portfolioId;

    private final ZonedDateTime createdAt;
    private ZonedDateTime lastRecomputedAt;

    /**
     * Builds a specification from the difference between a snapshot and what is already known.
     *
     * @throws NothingToSynchroniseException when the two agree, because an empty spec is a
     *                                       pending decision nobody can act on
     */
    public static PortfolioSpec from(
            PortfolioSpecId id,
            UserId userId,
            String connectionId,
            List<Asset> knownState,
            ExchangeSnapshot snapshot,
            ZonedDateTime now) {

        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(knownState, "knownState is required");
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(now, "now is required");

        List<Difference> differences = DifferenceEngine.compute(knownState, snapshot);
        if (differences.isEmpty()) {
            throw new NothingToSynchroniseException(userId);
        }

        return PortfolioSpec.builder()
                .id(id)
                .userId(userId)
                .connectionId(connectionId)
                .snapshot(snapshot)
                .differences(differences)
                .status(statusFor(differences))
                .createdAt(now)
                .lastRecomputedAt(now)
                .build();
    }

    /**
     * {@code DRAFT} when nothing needs a human — the path most synchronisations take, and the
     * reason D4 exists. Without those rules every difference would land in
     * {@code AWAITING_ANSWER}, including an ordinary purchase the exchange already priced.
     */
    private static SpecStatus statusFor(List<Difference> differences) {
        return differences.stream().anyMatch(Difference::needsAnswer)
                ? SpecStatus.AWAITING_ANSWER
                : SpecStatus.DRAFT;
    }

    /**
     * Records what the user said about one batch (task D2).
     *
     * <p>The answer is matched on {@code (ticker, subName, quantity)} — the batch, not the
     * position. An answer whose anchor no longer exists is rejected rather than applied to
     * whichever difference looks closest: the specification is untrusted input and the snapshot
     * is the anchor (§4.7).
     */
    public void answer(Ticker ticker, SubName subName, Quantity quantity, Answer given) {
        requireNotTerminal("answer");

        List<Difference> updated = new ArrayList<>(differences.size());
        boolean matched = false;
        for (Difference difference : differences) {
            if (!matched && matches(difference, ticker, subName, quantity)) {
                if (!difference.needsAnswer()) {
                    throw new AnswerNotApplicableException(String.format(
                            "[%s/%s] is not waiting for an answer", ticker.getId(), subName.getName()));
                }
                try {
                    updated.add(difference.answeredWith(given));
                } catch (IllegalArgumentException e) {
                    throw new AnswerNotApplicableException(e.getMessage());
                }
                matched = true;
            } else {
                updated.add(difference);
            }
        }
        if (!matched) {
            throw new AnswerNotApplicableException(String.format(
                    "no open question for [%s/%s] of [%s]",
                    ticker.getId(), subName.getName(), quantity));
        }

        this.differences = List.copyOf(updated);
        this.status = needsAnswers() ? SpecStatus.AWAITING_ANSWER : SpecStatus.CONFIRMED;
    }

    /**
     * Everything is decided and the portfolio has been written (task D3).
     *
     * @throws UnansweredQuestionsException when something is still open — silence is not an answer
     */
    public void markApplied(PortfolioId portfolioId, ZonedDateTime now) {
        requireNotTerminal("apply");
        if (needsAnswers()) {
            throw new UnansweredQuestionsException(id, openQuestions().size());
        }
        this.portfolioId = Objects.requireNonNull(portfolioId, "portfolioId is required");
        this.status = SpecStatus.APPLIED;
        this.lastRecomputedAt = now;
    }

    /** Writing the portfolio failed. Recoverable — the decisions are still valid. */
    public void markFailed() {
        requireNotTerminal("fail");
        this.status = SpecStatus.FAILED;
    }

    /** Batches are matched with a tolerance because {@code Quantity} is backed by a double (F2). */
    private static boolean matches(Difference difference, Ticker ticker, SubName subName, Quantity quantity) {
        return difference.ticker().equals(ticker)
                && difference.subName().equals(subName)
                && Math.abs(difference.quantity().getQty() - quantity.getQty()) < 1e-9;
    }

    public List<Difference> openQuestions() {
        return differences.stream().filter(Difference::needsAnswer).toList();
    }

    public boolean needsAnswers() {
        return !openQuestions().isEmpty();
    }

    /** True once the anchor is older than the TTL — a hint for the interface, not a correctness rule. */
    public boolean isSnapshotExpired(ZonedDateTime now) {
        return snapshot.takenAt().plus(SNAPSHOT_TTL).isBefore(now);
    }

    /**
     * Recomputes against a newer snapshot after the anchor aged out.
     *
     * <p>D2 will extend this to carry over answers whose batch survived; until answers exist
     * there is nothing to carry.
     */
    public void markStale(ExchangeSnapshot fresherSnapshot, List<Asset> knownState, ZonedDateTime now) {
        requireNotTerminal("recompute");
        this.differences = DifferenceEngine.compute(knownState, fresherSnapshot);
        this.status = differences.isEmpty() ? SpecStatus.APPLIED : statusFor(differences);
        this.lastRecomputedAt = now;
    }

    /**
     * The user walked away. Terminal and deliberate: without it a spec sits in
     * {@code AWAITING_ANSWER} forever and "thinking" is indistinguishable from "gone".
     */
    public void cancel() {
        requireNotTerminal("cancel");
        this.status = SpecStatus.CANCELLED;
    }

    private void requireNotTerminal(String operation) {
        if (status == SpecStatus.APPLIED || status == SpecStatus.CANCELLED) {
            throw new IllegalSpecTransitionException(id, status, operation);
        }
    }
}
