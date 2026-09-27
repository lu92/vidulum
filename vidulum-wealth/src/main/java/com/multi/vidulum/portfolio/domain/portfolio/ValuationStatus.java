package com.multi.vidulum.portfolio.domain.portfolio;

/**
 * Whether everything held could be priced (task C16).
 *
 * <p>A position nobody quotes used to take the whole read down: {@code GET /portfolio} prices
 * every asset, and one miss threw, so the owner got a 404 instead of a portfolio. That became
 * reachable when the snapshot started covering the funding account — where anything ever
 * deposited sits, including tokens the exchange does not quote — and B4's currency chain cannot
 * help, since no exchange rate brings back a price the market never published.
 *
 * <p>So the position becomes a gap: the row is there, with its quantity, and its value is silent.
 * What this status adds is the portfolio's own honesty — because a total summed over what could be
 * priced is <b>not</b> the portfolio's value, and without saying so it would read as if it were.
 */
public enum ValuationStatus {

    /** Every position could be priced. The total is the portfolio's value. */
    COMPLETE,

    /**
     * Some positions could not be priced. The total covers the rest, and the payload names what is
     * missing — the one thing that cannot be said is <b>how much</b> is missing, since that is
     * exactly the figure nobody can price.
     */
    PARTIAL,

    /** Nothing held could be priced. Not the same claim as an empty portfolio worth zero. */
    NOTHING_PRICED,

    /** Nothing is held. There was nothing to price. */
    NOTHING_HELD
}
