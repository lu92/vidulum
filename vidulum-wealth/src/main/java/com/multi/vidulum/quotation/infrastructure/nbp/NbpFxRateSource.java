package com.multi.vidulum.quotation.infrastructure.nbp;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.domain.fx.FxRateSource;
import com.multi.vidulum.quotation.domain.fx.RateSourceId;
import lombok.AllArgsConstructor;
import org.springframework.web.client.HttpClientErrorException;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

/**
 * Exchange rates as published by the Polish central bank.
 *
 * <p>Answers for the złoty and nothing else, because that is all this institution publishes: its
 * tables are "currency X against PLN". Asking it for USD/EUR is not an outage, so it is an empty
 * answer rather than an exception - and so is a currency it does not list, which arrives as a 404.
 *
 * <p>The moment carried is <b>the day the rate applies to</b>, at its start in Warsaw. The fixing
 * is a day's rate, not a tick; inventing a publication minute would make it look fresher than it
 * is, and the whole point of carrying {@code asOf} is that a chained price is only as fresh as its
 * older half.
 */
@AllArgsConstructor
public class NbpFxRateSource implements FxRateSource {

    public static final RateSourceId NBP = RateSourceId.of("NBP");
    private static final Currency ZLOTY = Currency.of("PLN");
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private final NbpApi api;

    @Override
    public RateSourceId id() {
        return NBP;
    }

    @Override
    public Optional<FxRate> latest(Currency base, Currency quote) {
        if (!ZLOTY.equals(quote) || ZLOTY.equals(base)) {
            return Optional.empty();
        }
        NbpApi.TableA table;
        try {
            table = api.averageRate(base.getId().toLowerCase(Locale.ROOT));
        } catch (HttpClientErrorException.NotFound unknownCurrency) {
            return Optional.empty();
        }
        if (table == null || table.rates() == null || table.rates().isEmpty()) {
            return Optional.empty();
        }
        NbpApi.TableA.Rate rate = table.rates().getFirst();
        return Optional.of(new FxRate(
                base,
                ZLOTY,
                Price.of(rate.mid(), ZLOTY.getId()),
                rate.effectiveDate().atStartOfDay(WARSAW),
                NBP));
    }
}
