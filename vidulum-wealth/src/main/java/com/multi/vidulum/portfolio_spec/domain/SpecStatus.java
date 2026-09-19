package com.multi.vidulum.portfolio_spec.domain;

/**
 * Lifecycle of a specification (§4.5).
 *
 * <pre>
 *   draft ──no questions──> applied
 *     │                        ▲
 *     ├──questions──> awaiting_answer ──answers──> confirmed ──fresh snapshot agrees──┘
 *     │                        │                       │
 *     ├──TTL──> stale ─────────┘                       └──snapshot changed──> awaiting_answer
 *     └──user gave up──> cancelled
 * </pre>
 *
 * <p>Only the transitions D1 owns are implemented here; {@code confirmed}, {@code applied} and
 * {@code failed} arrive with D2 and D3.
 */
public enum SpecStatus {

    /** Freshly computed. Either there is nothing to ask, or the questions have not been read yet. */
    DRAFT,

    /** Someone has to answer before this can be applied. */
    AWAITING_ANSWER,

    /** Answered, not yet applied. */
    CONFIRMED,

    /** The portfolio was written. Terminal. */
    APPLIED,

    /**
     * The snapshot aged out. Recomputing keeps the answers whose batch still holds and asks only
     * about what is new — the snapshot expires, the answers do not.
     */
    STALE,

    /** The user abandoned onboarding. Terminal, and deliberate: without it specs hang forever. */
    CANCELLED,

    /** Writing the portfolio failed. Recoverable. */
    FAILED
}
