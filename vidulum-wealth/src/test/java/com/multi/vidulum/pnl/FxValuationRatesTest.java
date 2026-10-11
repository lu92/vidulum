package com.multi.vidulum.pnl;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.pnl.domain.ValuationBasis;
import com.multi.vidulum.pnl.infrastructure.FxValuationRates;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.domain.fx.FxRates;
import com.multi.vidulum.quotation.domain.fx.RateSourceId;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a valuation's basis off the rates the backend keeps (task C17 over B4).
 */
class FxValuationRatesTest {

    private static final RateSourceId NBP = RateSourceId.of("NBP");
    private static final ZonedDateTime FRIDAY = ZonedDateTime.parse("2026-09-25T00:00+02:00[Europe/Warsaw]");

    private final FxRates rates = new FxRates();
    private final FxValuationRates valuationRates = new FxValuationRates(rates);

    @Test
    void shouldReadTheRateOfTheValuationCurrencyAgainstThePivot() {
        rates.record(FxRate.of("USD", "PLN", 3.8404, FRIDAY, NBP));

        Optional<ValuationBasis> basis = valuationRates.basisFor(Currency.of("PLN"));

        assertThat(basis).isPresent();
        assertThat(basis.get().currency()).isEqualTo(Currency.of("PLN"));
        assertThat(basis.get().rate().symbol().getId())
                .as("the valuation currency expressed in the pivot, inverted from the held fixing")
                .isEqualTo("PLN/USD");
        assertThat(basis.get().rate().asOf()).isEqualTo(FRIDAY);
    }

    @Test
    void shouldNeedNothingHeldForThePivotItself() {
        assertThat(valuationRates.basisFor(Currency.of("USD")))
                .contains(ValuationBasis.pivot(Currency.of("USD")));
    }

    /**
     * Nothing held is empty rather than an invented rate of one. The handler then records the
     * currency without a rate — an entry that admits what it does not know, instead of one that
     * quietly claims parity with the dollar.
     */
    @Test
    void shouldAnswerEmptyWhenNoRateIsHeld() {
        assertThat(valuationRates.basisFor(Currency.of("PLN"))).isEmpty();
    }
}
