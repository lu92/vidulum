package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;

import java.util.Objects;

/**
 * One thing that changed between what we knew and what the exchange says — already classified by
 * the rules in {@link ResolutionRules} as either settled or a question.
 *
 * <p>It deliberately carries no more of the asset than it needs: the batch it concerns, and the
 * cost if one is known. A difference that mirrored every field of a position would turn the spec
 * into a second portfolio model under another name (§4.7).
 *
 * @param quantity     the batch this difference is about — an answer is anchored to it, so buying
 *                     more later creates a new batch instead of invalidating the earlier answer
 * <p>Three shapes, and no fourth:
 *
 * <ul>
 *   <li>{@code question == null} — the rules settled it, and {@code resolvedCost} says what it cost;
 *   <li>{@code question != null, answer == null} — open, waiting for a human;
 *   <li>{@code question != null, answer != null} — answered. The cost is set when the answer
 *       supplied one, and stays {@code null} when the user explicitly said they do not know.
 * </ul>
 *
 * @param resolvedCost what the units cost, from the rules or from the answer
 * @param question     what to ask; {@code null} when the rules settled it
 * @param answer       what the user said; {@code null} while the question is open
 */
public record Difference(
        Ticker ticker,
        SubName subName,
        DifferenceDirection direction,
        Quantity quantity,
        CostBasis resolvedCost,
        QuestionKind question,
        Answer answer) {

    public Difference {
        Objects.requireNonNull(ticker, "ticker is required");
        Objects.requireNonNull(subName, "subName is required");
        Objects.requireNonNull(direction, "direction is required");
        Objects.requireNonNull(quantity, "quantity is required");
        if (question == null && resolvedCost == null) {
            throw new IllegalArgumentException(
                    "a difference the rules settled must carry a cost: " + ticker.getId());
        }
        if (question == null && answer != null) {
            throw new IllegalArgumentException(
                    "a difference the rules settled cannot carry an answer: " + ticker.getId());
        }
        if (answer == null && question != null && resolvedCost != null) {
            throw new IllegalArgumentException(
                    "an open question cannot already carry a cost: " + ticker.getId());
        }
    }

    public static Difference settled(
            Ticker ticker, SubName subName, DifferenceDirection direction,
            Quantity quantity, CostBasis cost) {
        return new Difference(ticker, subName, direction, quantity, cost, null, null);
    }

    public static Difference asking(
            Ticker ticker, SubName subName, DifferenceDirection direction,
            Quantity quantity, QuestionKind question) {
        return new Difference(ticker, subName, direction, quantity, null, question, null);
    }

    public boolean needsAnswer() {
        return question != null && answer == null;
    }

    public boolean isAnswered() {
        return answer != null;
    }

    /**
     * Applies an answer to this difference.
     *
     * <p>A supplied cost becomes {@code USER_PROVIDED}, which is the one provenance that may
     * never be overwritten without asking — that protection is the reason the dictionary is
     * closed.
     */
    public Difference answeredWith(Answer given) {
        if (question == null) {
            throw new IllegalArgumentException(
                    "the rules already settled this difference: " + ticker.getId());
        }
        if (!given.kind().answers(question)) {
            throw new IllegalArgumentException(String.format(
                    "answer [%s] does not answer question [%s] for [%s]",
                    given.kind(), question, ticker.getId()));
        }
        CostBasis cost = given.kind() == AnswerKind.COST_PROVIDED
                ? CostBasis.of(quantity, given.avgPrice(), Provenance.USER_PROVIDED)
                : null;
        return new Difference(ticker, subName, direction, quantity, cost, question, given);
    }
}
