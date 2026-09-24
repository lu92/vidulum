package com.multi.vidulum.common;

/**
 * Where a {@link CostBasis} number came from.
 *
 * <p>The dictionary is closed on purpose: it is what makes the rule "what may be overwritten
 * without asking" decidable. A number the exchange reported can be replaced silently by a better
 * one; a number a human typed cannot.
 *
 * <p>There is deliberately <b>no</b> {@code UNKNOWN} value. "We do not know the cost" is
 * expressed by a {@code null} {@link CostBasis} and by nothing else — two ways of saying it would
 * guarantee that some code checks one and forgets the other.
 */
public enum Provenance {

    /** Reported by the exchange, e.g. OKX {@code openAvgPx} / {@code accAvgPx}. */
    EXCHANGE_REPORTED,

    /** Computed from trades we recorded ourselves. */
    DERIVED_FROM_FILLS,

    /** Fiat or a stablecoin taken at par — one unit cost one unit. Replaces the old sentinel. */
    ASSUMED_PAR,

    /** The user told us. Never overwritten without asking. */
    USER_PROVIDED,

    /**
     * What an exchange account was worth on the day we started watching it (task C12).
     *
     * <p>Only ever carried by a {@code Contribution}, never by a {@code CostBasis}. The exchange
     * did not say "this is what you put in" — it said "this is what you hold". Recording that
     * difference in the data, rather than in a comment, is what stops an opening balance from
     * being read later as a deposit somebody actually made.
     */
    OPENING_SNAPSHOT;

    /**
     * Whether a better number may replace this one silently. Only {@link #USER_PROVIDED} may not:
     * overwriting a person's answer behind their back is how a portfolio quietly stops matching
     * what its owner believes.
     */
    public boolean isOverwritableSilently() {
        return this != USER_PROVIDED;
    }
}
