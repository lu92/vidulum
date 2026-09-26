package com.multi.vidulum.quotation.domain.fx;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Symbol;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the backend currently believes about exchange rates — the thing it looks after by itself.
 *
 * <p>Holds the last rate seen per pair and nothing more: no fetching, no schedule, no HTTP. Who
 * refreshes it is {@link com.multi.vidulum.quotation.app.FxRateRefresher}'s business and where the
 * numbers come from is {@link FxRateSource}'s, which is what lets a test state a rate outright
 * instead of standing up a central bank.
 *
 * <p>Keeping the last known rate rather than expiring it is deliberate. Friday's fixing is the
 * right answer all weekend, and a source that fails on Monday leaves the reader better off with
 * Friday's rate — labelled with Friday — than with no valuation at all. Age travels in
 * {@link FxRate#asOf()}, so staleness is visible rather than enforced here.
 */
@Slf4j
public class FxRates {

    private final ConcurrentHashMap<Symbol, FxRate> known = new ConcurrentHashMap<>();

    public void record(FxRate rate) {
        known.put(rate.symbol(), rate);
        log.info("Exchange rate [{}] is [{}] as of [{}] according to [{}]",
                rate.symbol().getId(), rate.rate().getAmount(), rate.asOf(), rate.source());
    }

    /**
     * What one currency is worth in the other, from either direction of the same observation.
     *
     * <p>A currency against itself is refused rather than answered with one: that is arithmetic,
     * settled before any source is consulted (the rule quote lookup already applies to {@code
     * EUR/EUR}), and answering it here would invent a moment and a source for a fact that has
     * neither.
     */
    public Optional<FxRate> rateOf(Currency base, Currency quote) {
        if (base.equals(quote)) {
            throw new IllegalArgumentException(
                    "A currency against itself is arithmetic, not a rate to look up: " + base.getId());
        }
        FxRate direct = known.get(Symbol.of(base.getId() + "/" + quote.getId()));
        if (direct != null) {
            return Optional.of(direct);
        }
        FxRate opposite = known.get(Symbol.of(quote.getId() + "/" + base.getId()));
        return Optional.ofNullable(opposite).map(FxRate::inverted);
    }

    /** The pairs held right now, as published — inversions are derived, not stored. */
    public Set<Symbol> knownPairs() {
        return Set.copyOf(known.keySet());
    }

    public void clear() {
        known.clear();
    }
}
