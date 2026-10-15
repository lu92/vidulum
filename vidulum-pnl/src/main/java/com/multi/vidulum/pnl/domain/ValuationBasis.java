package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.quotation.api.fx.FxRate;

import java.util.Objects;
import java.util.Optional;

/**
 * The currency a recorded valuation is expressed in, and what that currency was worth when it was
 * recorded (task C17).
 *
 * <p>A stored figure without this says less than it appears to. Read by someone who lives in
 * złoty, a history kept in dollars moves for two reasons at once — the portfolio and the exchange
 * rate — and no amount of arithmetic separates them afterwards from the number alone. A valuation
 * cannot be recomputed later either: last Tuesday's rate is not available on Friday, so what is
 * not written down at the time is lost for good.
 *
 * <p>Recording <b>both</b> the currency and the rate is deliberate, and it is what makes the
 * harder question — which currency a history ought to be kept in — answerable later rather than
 * now. With the rate stored, either side can be reconstructed; without it, the choice made today
 * is the only one this history will ever support.
 *
 * @param currency what the amounts are expressed in
 * @param rate     what one unit of {@code currency} was worth in the pivot, as of the moment that
 *                 rate applies to and according to whoever published it. Absent when the currency
 *                 <b>is</b> the pivot: one dollar in dollars is arithmetic, with no moment and no
 *                 source, and inventing a rate there would be inventing a provenance too.
 */
public record ValuationBasis(Currency currency, FxRate rate) {

    public ValuationBasis {
        Objects.requireNonNull(currency, "a valuation must say what currency it is in");
        if (rate != null && !rate.base().equals(currency)) {
            throw new IllegalArgumentException(
                    "a valuation in %s cannot be based on a rate for %s"
                            .formatted(currency.getId(), rate.base().getId()));
        }
    }

    /** A valuation in the pivot currency itself, which needs no rate to be understood. */
    public static ValuationBasis pivot(Currency currency) {
        return new ValuationBasis(currency, null);
    }

    public Optional<FxRate> knownRate() {
        return Optional.ofNullable(rate);
    }
}
