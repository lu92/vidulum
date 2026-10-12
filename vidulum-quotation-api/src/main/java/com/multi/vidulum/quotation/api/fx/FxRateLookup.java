package com.multi.vidulum.quotation.api.fx;

import com.multi.vidulum.common.Currency;

import java.util.Optional;

/**
 * What one currency is worth in another, as far as the backend knows.
 *
 * <p>Reading only. The keeper behind this interface is mutable state — rates are recorded by a
 * refresher on a schedule — and that half has no business crossing a module boundary: a consumer
 * able to call {@code record} or {@code clear} could silently rewrite what every other consumer
 * then values a portfolio against.
 *
 * <p>Separating the two halves is also what lets a test state a rate outright instead of standing
 * up a central bank.
 */
public interface FxRateLookup {

    /**
     * The pair from either direction of the same observation — an inverted rate keeps the moment
     * and the source of the one it came from.
     *
     * @return empty when no source has published the pair yet
     * @throws IllegalArgumentException for a currency against itself, which is arithmetic rather
     *         than an observation and has neither a moment nor a source
     */
    Optional<FxRate> rateOf(Currency base, Currency quote);
}
