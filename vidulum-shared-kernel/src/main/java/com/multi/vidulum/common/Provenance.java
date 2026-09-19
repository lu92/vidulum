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
    USER_PROVIDED;

    /**
     * Whether a better number may replace this one silently. Only {@link #USER_PROVIDED} may not:
     * overwriting a person's answer behind their back is how a portfolio quietly stops matching
     * what its owner believes.
     */
    public boolean isOverwritableSilently() {
        return this != USER_PROVIDED;
    }
}
