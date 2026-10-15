package com.multi.vidulum.portfolio.domain.portfolio;

/**
 * Why a portfolio's net contribution figure is, or is not, there (task C9).
 *
 * <p>Deliberately parallel to {@link ProfitStatus} rather than shared with it. The shapes match
 * because the rule matches — a number computed from a minority of what it describes is withheld
 * rather than annotated — but the two answer different questions, and one enum serving both would
 * force values like {@code NO_KNOWN_COST} onto a field that has nothing to do with cost.
 *
 * <p>The field this replaces used to answer {@code 0} for a portfolio built from an exchange
 * snapshot, which is the one thing worse than answering nothing: zero is a number, and an
 * interface will render it as one.
 */
public enum ContributionStatus {

    /** Enough of what came in has a known value for the total to stand for it. */
    COMPUTED,

    /**
     * Some values are known, too little of the ledger — the figure is withheld and
     * {@code contributionCoverage} says how little. See {@link ProfitCoverage#MEANINGFUL_FROM}.
     */
    WITHHELD_LOW_COVERAGE,

    /** Contributions are recorded, none of them valued. Nothing to add up. */
    NO_KNOWN_VALUE,

    /** Nothing has ever moved in or out. Not the same claim as "we do not know what did". */
    NOTHING_CONTRIBUTED
}
