package com.multi.vidulum.pnl.infrastructure.entities;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.pnl.domain.ValuationBasis;
import com.multi.vidulum.quotation.api.fx.FxRate;
import com.multi.vidulum.quotation.api.fx.RateSourceId;
import lombok.Builder;
import lombok.Value;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

/**
 * How a valuation's basis is written to the database (task C17).
 *
 * <p>A separate shape from {@link ValuationBasis} for one concrete reason, found on a running
 * container rather than in a test: the driver has no codec for {@code ZonedDateTime}, so storing
 * the domain object directly failed the whole snapshot — every other date in this module is
 * already flattened to a {@link Date} for exactly that reason, and the rate carries one too.
 *
 * <p>The moment is kept in UTC and read back as UTC. The rate's own zone was Warsaw, which is not
 * lost in any way that matters: what the field states is an instant, and the day the fixing
 * applies to is a property of the fixing, not of how it is displayed.
 */
@Value
@Builder
public class ValuationBasisEntity {

    String currency;

    /** All null together when no rate was held — see {@code ValuationBasis}. */
    java.math.BigDecimal rate;
    String rateCurrency;
    Date rateAsOf;
    String rateSource;

    public static ValuationBasisEntity from(ValuationBasis basis) {
        if (basis == null) {
            return null;
        }
        return basis.knownRate()
                .map(rate -> ValuationBasisEntity.builder()
                        .currency(basis.currency().getId())
                        .rate(rate.rate().getAmount())
                        .rateCurrency(rate.rate().getCurrency())
                        .rateAsOf(Date.from(rate.asOf().toInstant()))
                        .rateSource(rate.source().id())
                        .build())
                .orElseGet(() -> ValuationBasisEntity.builder()
                        .currency(basis.currency().getId())
                        .build());
    }

    public ValuationBasis toDomain() {
        Currency valuationCurrency = Currency.of(currency);
        if (rate == null || rateAsOf == null || rateSource == null) {
            return ValuationBasis.pivot(valuationCurrency);
        }
        return new ValuationBasis(valuationCurrency, new FxRate(
                valuationCurrency,
                Currency.of(rateCurrency),
                Price.of(rate, rateCurrency),
                ZonedDateTime.ofInstant(rateAsOf.toInstant(), ZoneOffset.UTC),
                RateSourceId.of(rateSource)));
    }
}
