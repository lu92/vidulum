package com.multi.vidulum.quotation.fx;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.quotation.app.FxRateRefresher;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.domain.fx.FxRateSource;
import com.multi.vidulum.quotation.domain.fx.FxRates;
import com.multi.vidulum.quotation.domain.fx.RateSourceId;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backend keeping its own currency rates current (task B4).
 *
 * <p>Exchange prices arrive because somebody published them; a fixing has no such sender. If the
 * refresh is what fills the keeper, then what it does when a source misbehaves decides whether a
 * portfolio can still be valued — which is what these cases are about.
 */
class FxRateRefresherTest {

    private static final RateSourceId NBP = RateSourceId.of("NBP");
    private static final ZonedDateTime FRIDAY = ZonedDateTime.parse("2026-09-25T00:00+02:00[Europe/Warsaw]");
    private static final ZonedDateTime MONDAY = ZonedDateTime.parse("2026-09-28T00:00+02:00[Europe/Warsaw]");

    /** A source whose answer per currency the test states outright, including "it throws". */
    private static final class StubSource implements FxRateSource {
        private final Map<String, FxRate> published = new HashMap<>();
        private final List<String> broken = new ArrayList<>();
        private final List<String> asked = new ArrayList<>();

        @Override
        public RateSourceId id() {
            return NBP;
        }

        @Override
        public Optional<FxRate> latest(Currency base, Currency quote) {
            asked.add(base.getId() + "/" + quote.getId());
            if (broken.contains(base.getId())) {
                throw new IllegalStateException("the bank is not answering");
            }
            return Optional.ofNullable(published.get(base.getId() + "/" + quote.getId()));
        }
    }

    private final StubSource source = new StubSource();
    private final FxRates rates = new FxRates();

    private FxRateRefresher refresherFor(String... pairs) {
        return new FxRateRefresher(source, rates, List.of(pairs).stream().map(Symbol::of).toList());
    }

    @Test
    void shouldRecordEveryPairItWatches() {
        source.published.put("USD/PLN", FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));
        source.published.put("EUR/PLN", FxRate.of("EUR", "PLN", 4.2610, FRIDAY, NBP));

        refresherFor("USD/PLN", "EUR/PLN").refresh();

        assertThat(rates.rateOf(Currency.of("USD"), Currency.of("PLN")))
                .contains(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));
        assertThat(rates.rateOf(Currency.of("EUR"), Currency.of("PLN")))
                .contains(FxRate.of("EUR", "PLN", 4.2610, FRIDAY, NBP));
    }

    /**
     * One currency failing must not cost the others their refresh — the same rule the daily
     * valuation job follows, for the same reason: the loop is over independent facts, and stopping
     * at the first failure punishes everything that comes after it alphabetically.
     */
    @Test
    void shouldRefreshTheOtherPairsWhenOneSourceCallFails() {
        source.broken.add("USD");
        source.published.put("EUR/PLN", FxRate.of("EUR", "PLN", 4.2610, MONDAY, NBP));

        refresherFor("USD/PLN", "EUR/PLN").refresh();

        assertThat(rates.rateOf(Currency.of("EUR"), Currency.of("PLN")))
                .contains(FxRate.of("EUR", "PLN", 4.2610, MONDAY, NBP));
    }

    /**
     * A failed refresh leaves the rate already held. Friday's fixing is nearly right on Monday and
     * carries its own date; dropping it would turn "the bank was unreachable for an hour" into
     * "this portfolio cannot be valued".
     */
    @Test
    void shouldKeepTheRateAlreadyHeldWhenTheSourceFails() {
        rates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));
        source.broken.add("USD");

        refresherFor("USD/PLN").refresh();

        assertThat(rates.rateOf(Currency.of("USD"), Currency.of("PLN")))
                .contains(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));
    }

    /** Same for a source that simply does not publish the pair: nothing gained, nothing lost. */
    @Test
    void shouldKeepTheRateAlreadyHeldWhenTheSourcePublishesNothing() {
        rates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        refresherFor("USD/PLN").refresh();

        assertThat(rates.rateOf(Currency.of("USD"), Currency.of("PLN")))
                .contains(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));
    }

    @Test
    void shouldAskOnlyForThePairsItWatches() {
        source.published.put("USD/PLN", FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        refresherFor("USD/PLN").refresh();

        assertThat(source.asked).containsExactly("USD/PLN");
        assertThat(rates.knownPairs()).containsExactly(Symbol.of("USD/PLN"));
    }

    @Test
    void shouldOverwriteAHeldRateWithANewerFixing() {
        rates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));
        source.published.put("USD/PLN", FxRate.of("USD", "PLN", 3.7011, MONDAY, NBP));

        refresherFor("USD/PLN").refresh();

        assertThat(rates.rateOf(Currency.of("USD"), Currency.of("PLN")))
                .contains(FxRate.of("USD", "PLN", 3.7011, MONDAY, NBP));
    }
}
