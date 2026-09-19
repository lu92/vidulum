package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.Price;

import java.util.Objects;

/**
 * What the user said about one difference.
 *
 * <p>An answer is anchored to the <b>batch</b> it was given for — the difference's
 * {@code (ticker, subName, quantity)} — not to the position as a whole. That is what lets a later
 * purchase create a new question without invalidating this one: buying more ETH does not change
 * what the earlier units cost (§4.5).
 */
public record Answer(AnswerKind kind, Price avgPrice) {

    public Answer {
        Objects.requireNonNull(kind, "kind is required");
        if (kind == AnswerKind.COST_PROVIDED && avgPrice == null) {
            throw new IllegalArgumentException("COST_PROVIDED requires a price");
        }
        if (kind != AnswerKind.COST_PROVIDED && avgPrice != null) {
            throw new IllegalArgumentException(kind + " does not take a price");
        }
    }

    public static Answer cost(Price avgPrice) {
        return new Answer(AnswerKind.COST_PROVIDED, avgPrice);
    }

    public static Answer costUnknown() {
        return new Answer(AnswerKind.COST_UNKNOWN, null);
    }

    public static Answer withdrawal() {
        return new Answer(AnswerKind.WITHDRAWAL, null);
    }

    public static Answer movedToOwnAccount() {
        return new Answer(AnswerKind.MOVED_TO_OWN_ACCOUNT, null);
    }
}
