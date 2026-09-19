package com.multi.vidulum.portfolio_spec.domain;

/**
 * What the user said. A closed dictionary, so later logic can act on it — free text would be
 * useless to a rule like "recompute everything that was only assumed".
 */
public enum AnswerKind {

    /** "They cost me this much." Produces a cost with {@code USER_PROVIDED} provenance. */
    COST_PROVIDED,

    /**
     * "I do not know what they cost." A decision, not a gap — §4.7 requires that positions
     * without a price be <b>explicitly confirmed as unknown</b> rather than passed over in
     * silence, because silence and "I don't know" are different things and only one is an answer.
     */
    COST_UNKNOWN,

    /** "That money left for good." */
    WITHDRAWAL,

    /** "I moved it to another account of mine." Not a disposal, so no gain to compute. */
    MOVED_TO_OWN_ACCOUNT;

    public boolean answers(QuestionKind question) {
        return switch (question) {
            case ACQUISITION_COST -> this == COST_PROVIDED || this == COST_UNKNOWN;
            case DISPOSAL_REASON -> this == WITHDRAWAL || this == MOVED_TO_OWN_ACCOUNT;
        };
    }
}
