package com.multi.vidulum.quotation.fx;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.domain.fx.FxRates;
import com.multi.vidulum.quotation.domain.fx.RateSourceId;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the backend currently believes about currencies (task B4).
 *
 * <p>The keeper is the answer to "who looks after the rate": not the caller who publishes exchange
 * prices, and not a lookup done at read time, but a small piece of state the backend refreshes on
 * its own and reads from while valuing portfolios.
 */
class FxRatesComponentTest {

    private static final RateSourceId NBP = RateSourceId.of("NBP");
    private static final Currency USD = Currency.of("USD");
    private static final Currency PLN = Currency.of("PLN");
    private static final ZonedDateTime FRIDAY = ZonedDateTime.parse("2026-09-25T00:00+02:00[Europe/Warsaw]");
    private static final ZonedDateTime MONDAY = ZonedDateTime.parse("2026-09-28T00:00+02:00[Europe/Warsaw]");

    private final FxRates rates = new FxRates();

    @Test
    void shouldKnowNothingUntilSomethingIsRecorded() {
        assertThat(rates.rateOf(USD, PLN)).isEmpty();
        assertThat(rates.knownPairs()).isEmpty();
    }

    @Test
    void shouldAnswerWithWhatWasRecorded() {
        rates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        assertThat(rates.rateOf(USD, PLN))
                .contains(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));
    }

    /**
     * Asked the other way round, the same observation answers — inverted, and still dated Friday.
     * Holding both directions separately would let them drift apart and disagree about the same
     * fact.
     */
    @Test
    void shouldAnswerTheOppositeDirectionFromTheSameObservation() {
        rates.record(FxRate.of("USD", "PLN", 4.0, FRIDAY, NBP));

        Optional<FxRate> backwards = rates.rateOf(PLN, USD);

        assertThat(backwards).isPresent();
        assertThat(backwards.get().rate()).isEqualTo(Price.of(0.25, "USD"));
        assertThat(backwards.get().asOf()).isEqualTo(FRIDAY);
        assertThat(backwards.get().source()).isEqualTo(NBP);
    }

    @Test
    void shouldReplaceAnOlderFixingWithANewerOne() {
        rates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));
        rates.record(FxRate.of("USD", "PLN", 3.7011, MONDAY, NBP));

        assertThat(rates.rateOf(USD, PLN))
                .contains(FxRate.of("USD", "PLN", 3.7011, MONDAY, NBP));
    }

    /**
     * Friday's rate is the right answer all weekend, so nothing here expires it. A stale rate is
     * visibly stale — it carries its own date — while an expired one is indistinguishable from a
     * currency nobody ever published, and would take the whole portfolio's valuation down with it.
     */
    @Test
    void shouldKeepARateInsteadOfExpiringIt() {
        rates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        assertThat(rates.rateOf(USD, PLN)).isPresent();
        assertThat(rates.rateOf(USD, PLN).get().asOf())
                .as("no younger than the fixing it came from")
                .isEqualTo(FRIDAY);
    }

    @Test
    void shouldRefuseACurrencyAgainstItself() {
        assertThatThrownBy(() -> rates.rateOf(PLN, PLN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arithmetic");
    }

    /** What it holds is what was published; the opposite direction is derived on the way out. */
    @Test
    void shouldListOnlyThePairsThatWerePublished() {
        rates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        assertThat(rates.knownPairs()).containsExactly(Symbol.of("USD/PLN"));
    }

    @Test
    void shouldForgetEverythingWhenCleared() {
        rates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        rates.clear();

        assertThat(rates.rateOf(USD, PLN)).isEmpty();
    }
}
