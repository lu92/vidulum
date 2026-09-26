package com.multi.vidulum.quotation.domain.fx;

import com.multi.vidulum.common.Currency;

import java.util.Optional;

/**
 * Somewhere the backend can ask what one currency is worth in another.
 *
 * <p>The port exists so the rule "the backend looks after this itself" has somewhere to live.
 * Exchange prices arrive from outside — the caller holds the exchange credentials and publishes
 * what it read (see the quote publishing path). A currency fixing is different: it is public,
 * anonymous, identical for every user, and nobody's portfolio should become unreadable because no
 * one happened to run a script today.
 *
 * <p>Two kinds of "no" are deliberately distinct. {@link Optional#empty()} means <b>this source
 * does not publish that pair</b> — asking the Polish central bank for USD/EUR is a category error,
 * not an outage, and retrying will not help. A source that is unreachable or broken <b>throws</b>,
 * because that is worth logging, worth retrying, and must not be mistaken for a currency nobody
 * quotes.
 */
public interface FxRateSource {

    RateSourceId id();

    /** The latest rate this source publishes for the pair, or empty if it publishes none. */
    Optional<FxRate> latest(Currency base, Currency quote);
}
