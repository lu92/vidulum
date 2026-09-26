package com.multi.vidulum.quotation.fx;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.domain.fx.RateSourceId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One currency in another, as of a moment, according to somebody (task B4).
 *
 * <p>The rate is the smallest piece of the denomination chain and the one that decides whether the
 * rest can be honest: everything downstream reads its moment to know how stale a chained price is
 * and its source to know who said so.
 */
class FxRateTest {

    private static final ZonedDateTime FRIDAY = ZonedDateTime.parse("2026-09-25T00:00+02:00[Europe/Warsaw]");
    private static final RateSourceId NBP = RateSourceId.of("NBP");

    @Test
    void shouldStateOneCurrencyInAnother() {
        FxRate rate = FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP);

        assertThat(rate.symbol().getId()).isEqualTo("USD/PLN");
        assertThat(rate.rate()).isEqualTo(Price.of(3.6455, "PLN"));
        assertThat(rate.asOf()).isEqualTo(FRIDAY);
        assertThat(rate.source()).isEqualTo(NBP);
    }

    /**
     * A rate of USD/PLN priced in dollars is not a slip of the pen — it is the direction of the
     * conversion reversed, which would multiply where it should divide and be wrong by a factor of
     * thirteen at today's rate.
     */
    @Test
    void shouldRefuseARatePricedInTheWrongCurrency() {
        assertThatThrownBy(() -> new FxRate(
                Currency.of("USD"), Currency.of("PLN"), Price.of(3.6455, "USD"), FRIDAY, NBP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be priced in PLN");
    }

    /** A currency against itself is arithmetic; carrying it as an observation invents a source. */
    @Test
    void shouldRefuseACurrencyAgainstItself() {
        assertThatThrownBy(() -> FxRate.of("PLN", "PLN", 1, FRIDAY, NBP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arithmetic");
    }

    @Test
    void shouldRefuseARateThatIsNotPositive() {
        assertThatThrownBy(() -> FxRate.of("USD", "PLN", 0, FRIDAY, NBP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxRate.of("USD", "PLN", -3.64, FRIDAY, NBP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseARateWithoutAMomentOrASource() {
        assertThatThrownBy(() -> new FxRate(
                Currency.of("USD"), Currency.of("PLN"), Price.of(3.6455, "PLN"), null, NBP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FxRate(
                Currency.of("USD"), Currency.of("PLN"), Price.of(3.6455, "PLN"), FRIDAY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Reading the same observation backwards is arithmetic on one fact, not a second fact — so the
     * inverted rate is exactly as old as the one it came from and names the same publisher. Were it
     * stamped with "now", a weekend-old fixing would look fresh the moment somebody asked for
     * PLN/USD instead of USD/PLN.
     */
    @Test
    void shouldInvertWithoutBecomingYounger() {
        FxRate inverted = FxRate.of("USD", "PLN", 4.0, FRIDAY, NBP).inverted();

        assertThat(inverted.symbol().getId()).isEqualTo("PLN/USD");
        assertThat(inverted.rate()).isEqualTo(Price.of(0.25, "USD"));
        assertThat(inverted.asOf()).isEqualTo(FRIDAY);
        assertThat(inverted.source()).isEqualTo(NBP);
    }

    @Test
    void shouldReturnWhereItStartedAfterTwoInversions() {
        FxRate rate = FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP);

        assertThat(rate.inverted().inverted().rate().getAmount())
                .isCloseTo(rate.rate().getAmount(), org.assertj.core.data.Offset.offset(new BigDecimal("0.0000001")));
    }

    @Test
    void shouldRestateAPriceInTheOtherCurrency() {
        FxRate rate = FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP);

        assertThat(rate.convert(Price.of(2_000, "USD")))
                .isEqualTo(Price.of(7_291, "PLN"));
    }

    /** Converting a price that is not in the base currency is a category error, not a rounding one. */
    @Test
    void shouldRefuseToConvertAPriceItHasNoRateFor() {
        FxRate rate = FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP);

        assertThatThrownBy(() -> rate.convert(Price.of(2_000, "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR");
    }
}
