package com.multi.vidulum.portfolio.domain.portfolio;

/**
 * Why a realised result is or is not being reported (task F6).
 *
 * <p>Parallel to {@link ProfitStatus} and {@link ContributionStatus} rather than shared with them:
 * they answer about different things, and one enum serving three questions would force every
 * reader to know which question they were holding.
 */
public enum RealisedStatus {

    /** Sales happened, their cost was known, and the total is stated. */
    COMPUTED,

    /**
     * Too little of what was sold had a known cost for the sum to describe the whole — the same
     * rule as C4, applied to sales instead of holdings.
     */
    WITHHELD_LOW_COVERAGE,

    /** Things were sold and nothing sold had a known cost (task C7). */
    NO_KNOWN_COST,

    /** Nothing has been sold. Distinct from "sold at no gain", which is a result of zero. */
    NOTHING_SOLD
}
