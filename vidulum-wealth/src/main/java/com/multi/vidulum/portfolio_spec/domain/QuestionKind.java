package com.multi.vidulum.portfolio_spec.domain;

/**
 * What the user is being asked. A closed dictionary, like {@code Provenance} — free text would be
 * useless to any later logic, and the interface needs to know which control to render.
 */
public enum QuestionKind {

    /** "How much did these units cost you?" — an increase the exchange did not price. */
    ACQUISITION_COST,

    /**
     * "Was this a withdrawal or a move between your own accounts?" — a decrease with no trade of
     * ours to explain it.
     */
    DISPOSAL_REASON
}
