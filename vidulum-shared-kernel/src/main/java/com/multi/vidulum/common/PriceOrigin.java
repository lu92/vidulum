package com.multi.vidulum.common;

/**
 * Where a price came from — the same question {@code Provenance} asks of a cost, asked of a quote.
 *
 * <p>Three prices that look identical in the payload are not equally certain. One was published for
 * exactly the pair that was asked about; one is a different pair standing in for it; one is two
 * prices multiplied together, and is therefore only as fresh as its older half. A reader who cannot
 * tell them apart will read the weakest as if it were the strongest, which is the failure this
 * whole line of work keeps meeting — an unlabelled figure gets trusted for what it looks like.
 */
public enum PriceOrigin {

    /** Published for this pair. */
    DIRECT,

    /** A neighbouring pair standing in — today {@code X/USDT} answering for {@code X/USD}. */
    SUBSTITUTED,

    /** Published by a rate source for exactly this pair of currencies — a central bank fixing. */
    FIXING,

    /** Two legs multiplied through a pivot currency — see the denomination chain (task B4). */
    CHAINED
}
