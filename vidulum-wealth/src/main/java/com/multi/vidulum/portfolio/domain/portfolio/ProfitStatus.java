package com.multi.vidulum.portfolio.domain.portfolio;

/**
 * Why an aggregate profit figure is, or is not, there (task C4).
 *
 * <p>Three different silences used to look identical in the payload — an empty portfolio, one
 * whose costs nobody knows, and one where the known part is too small to speak for the whole.
 * A client seeing {@code null} could not tell them apart, and would have to guess a message.
 */
public enum ProfitStatus {

    /** Computed from a representative share of the value. The figure is there. */
    COMPUTED,

    /**
     * Some cost is known, but too little of the value — the figure is deliberately absent, and
     * {@code profitCoverage} says how little. See {@link ProfitCoverage#MEANINGFUL_FROM}.
     */
    WITHHELD_LOW_COVERAGE,

    /** Positions are held, none of them with a known cost. Nothing to compute from. */
    NO_KNOWN_COST,

    /** Nothing is held at all. Not the same claim as "costs are unknown". */
    NOTHING_HELD
}
