package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.Currency;

import java.util.Optional;

/**
 * What the valuation currency was worth when a snapshot was taken (task C17).
 *
 * <p>A port rather than a direct read of the rate keeper, because recording history must not
 * depend on how rates happen to be kept: a test states the rate outright, and the day a second
 * source appears this side does not change. Empty means no rate is held — which is a fact worth
 * recording as absent, not a reason to abandon the snapshot, since a valuation without its rate is
 * still the valuation.
 */
public interface ValuationRates {

    Optional<ValuationBasis> basisFor(Currency currency);
}
