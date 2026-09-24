package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Provenance;

import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Something the owner moved into this portfolio, or out of it (task C9).
 *
 * <p>Replaces the single {@code investedBalance} the aggregate used to carry. A scalar could only
 * work by picking a currency and a moment, and the moment is exactly what a portfolio built from
 * an exchange snapshot does not have at the time it is written — which is why that field sat at
 * zero beside a portfolio worth 147 000 EUR.
 *
 * <p><b>A contribution is not a cost basis.</b> Move one bitcoin from another exchange and your
 * contribution <i>to this portfolio</i> is one bitcoin, worth whatever it was worth on arrival;
 * what it cost you in 2019 on a different venue is a separate fact, and {@link com.multi.vidulum.common.CostBasis}
 * answers it. They measure from different boundaries — ownership of the asset, versus this
 * account — and letting arrival value leak into cost would rebuild, one level up, the lie C1
 * removed.
 *
 * @param valueAtArrival what it was worth, in the portfolio's own currency, when it arrived.
 *                       <b>May be absent</b>: we can know that one bitcoin came in on a given day
 *                       without knowing its price that day. Same move as a position with no cost —
 *                       and it is what lets the timeline exist before historical prices do (C13).
 */
public record Contribution(
        String id,
        ZonedDateTime when,
        Direction direction,
        Money what,
        Money valueAtArrival,
        Provenance provenance) {

    public Contribution {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(when, "when is required");
        Objects.requireNonNull(direction, "direction is required");
        Objects.requireNonNull(what, "what is required");
        if (valueAtArrival == null && provenance != null) {
            throw new IllegalArgumentException(
                    "a contribution with no value cannot claim where that value came from");
        }
        if (valueAtArrival != null && provenance == null) {
            throw new IllegalArgumentException(
                    "a contribution that states a value must say where it came from");
        }
    }

    /**
     * In or out. A direction rather than a negative amount: {@code Money} and {@code Quantity} are
     * non-negative everywhere else in this model, and a single negative value would open a case
     * every reader would then have to remember.
     */
    public enum Direction {
        IN,
        OUT
    }

    public static Contribution paidIn(String id, Money money, ZonedDateTime when) {
        return new Contribution(id, when, Direction.IN, money, money, Provenance.ASSUMED_PAR);
    }

    public static Contribution takenOut(String id, Money money, ZonedDateTime when) {
        return new Contribution(id, when, Direction.OUT, money, money, Provenance.ASSUMED_PAR);
    }

    /**
     * What an account was worth when we first read it — one entry standing for a history we do not
     * have (task C12). Replaced by real deposits once backfill exists (C13), which is why every
     * contribution carries an id: you cannot replace what you cannot point at.
     */
    public static Contribution opening(Money value, ZonedDateTime when) {
        return new Contribution(UUID.randomUUID().toString(), when, Direction.IN,
                value, value, Provenance.OPENING_SNAPSHOT);
    }

    public boolean hasKnownValue() {
        return valueAtArrival != null;
    }

    /** Signed by direction, so a ledger sums without every caller re-deriving the sign. */
    public Money signedValue() {
        if (valueAtArrival == null) {
            throw new IllegalStateException("contribution [" + id + "] has no value to sign");
        }
        return direction == Direction.IN ? valueAtArrival : valueAtArrival.multiply(-1);
    }
}
