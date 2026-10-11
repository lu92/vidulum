package com.multi.vidulum.pnl;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.pnl.domain.ValuationBasis;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.domain.fx.RateSourceId;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a recorded valuation is expressed in, and what that was worth at the time (task C17).
 *
 * <p>The pair is what makes a stored figure readable years later. A history kept in dollars and
 * read by somebody who lives in złoty moves for two reasons at once, and the number alone can
 * never be separated back out — while the rate that would separate it cannot be fetched
 * afterwards, because last Tuesday's rate is not available on Friday.
 */
class ValuationBasisTest {

    private static final RateSourceId NBP = RateSourceId.of("NBP");
    private static final ZonedDateTime FRIDAY = ZonedDateTime.parse("2026-09-25T00:00+02:00[Europe/Warsaw]");

    @Test
    void shouldCarryTheCurrencyAndWhatItWasWorth() {
        ValuationBasis basis = new ValuationBasis(
                Currency.of("PLN"), FxRate.of("PLN", "USD", 0.26, FRIDAY, NBP));

        assertThat(basis.currency()).isEqualTo(Currency.of("PLN"));
        assertThat(basis.knownRate()).isPresent();
        assertThat(basis.rate().asOf())
                .as("dated the day the fixing applies to, so staleness stays visible")
                .isEqualTo(FRIDAY);
        assertThat(basis.rate().source()).isEqualTo(NBP);
    }

    /**
     * The pivot needs no rate. One dollar in dollars is arithmetic — it has no moment and nobody
     * published it, so stating a rate there would invent a provenance as well as a number.
     */
    @Test
    void shouldNeedNoRateForThePivotItself() {
        ValuationBasis basis = ValuationBasis.pivot(Currency.of("USD"));

        assertThat(basis.currency()).isEqualTo(Currency.of("USD"));
        assertThat(basis.knownRate()).isEmpty();
    }

    /**
     * A rate for a different currency would describe something else entirely — and, being the
     * thing that reconstructs the figure later, would corrupt exactly the reading this exists for.
     */
    @Test
    void shouldRefuseARateThatIsNotAboutItsOwnCurrency() {
        assertThatThrownBy(() -> new ValuationBasis(
                Currency.of("PLN"), FxRate.of("EUR", "USD", 1.08, FRIDAY, NBP)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLN");
    }

    @Test
    void shouldRefuseAValuationThatDoesNotSayWhatCurrencyItIsIn() {
        assertThatThrownBy(() -> new ValuationBasis(null, null))
                .isInstanceOf(NullPointerException.class);
    }

    /** No rate held is a legitimate state: an incomplete entry beats a permanent hole. */
    @Test
    void shouldAllowACurrencyWhoseRateIsNotKnown() {
        ValuationBasis basis = new ValuationBasis(Currency.of("PLN"), null);

        assertThat(basis.currency()).isEqualTo(Currency.of("PLN"));
        assertThat(basis.knownRate()).isEmpty();
    }
}
