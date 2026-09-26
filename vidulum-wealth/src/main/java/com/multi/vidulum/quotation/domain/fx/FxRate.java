package com.multi.vidulum.quotation.domain.fx;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;

/**
 * One currency expressed in another, as of a moment, according to somebody.
 *
 * <p>All four parts are load-bearing. Without {@code asOf} a rate cannot be told from a stale one,
 * and a central bank's fixing is stale by construction — it is a day's rate, not a tick, so a
 * portfolio read on Sunday evening is using Friday's. Without {@code source} the reader cannot tell
 * an official fixing from a market quote standing in for one.
 */
public record FxRate(Currency base, Currency quote, Price rate, ZonedDateTime asOf, RateSourceId source) {

    /** Enough digits that inverting twice returns where it started for any realistic rate. */
    private static final int INVERSION_SCALE = 12;

    public FxRate {
        if (base == null || quote == null || rate == null || asOf == null || source == null) {
            throw new IllegalArgumentException("An exchange rate needs both currencies, a price, a moment and a source");
        }
        if (base.equals(quote)) {
            throw new IllegalArgumentException(
                    "A currency against itself is arithmetic, not an observation: " + base.getId());
        }
        if (!quote.getId().equals(rate.getCurrency())) {
            throw new IllegalArgumentException(
                    "A rate of %s/%s must be priced in %s, was %s"
                            .formatted(base.getId(), quote.getId(), quote.getId(), rate.getCurrency()));
        }
        if (rate.getAmount().signum() <= 0) {
            throw new IllegalArgumentException(
                    "An exchange rate must be positive, was " + rate.getAmount());
        }
    }

    public static FxRate of(String base, String quote, double rate, ZonedDateTime asOf, RateSourceId source) {
        return new FxRate(Currency.of(base), Currency.of(quote), Price.of(rate, quote), asOf, source);
    }

    public Symbol symbol() {
        return Symbol.of(Ticker.of(base.getId()), Ticker.of(quote.getId()));
    }

    /**
     * The same observation read the other way round. Arithmetic on one fact, not a second fact —
     * so it keeps the moment and the source of the rate it came from, and a reader asking how old
     * the inverted rate is gets the truth rather than "just now".
     */
    public FxRate inverted() {
        BigDecimal inverted = BigDecimal.ONE.divide(rate.getAmount(), INVERSION_SCALE, RoundingMode.HALF_UP);
        return new FxRate(quote, base, Price.of(inverted, base.getId()), asOf, source);
    }

    /** Restates a price given in {@link #base} as a price in {@link #quote}. */
    public Price convert(Price price) {
        if (!price.getCurrency().equals(base.getId())) {
            throw new IllegalArgumentException(
                    "A rate of %s/%s cannot convert a price in %s"
                            .formatted(base.getId(), quote.getId(), price.getCurrency()));
        }
        return Price.of(price.getAmount().multiply(rate.getAmount()), quote.getId());
    }
}
