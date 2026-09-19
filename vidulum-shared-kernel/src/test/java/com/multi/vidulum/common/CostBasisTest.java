package com.multi.vidulum.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The type exists to make one mistake impossible: multiplying the known part's price by the
 * whole balance. Everything here is about that, plus the rule that decides what may be
 * overwritten without asking.
 */
class CostBasisTest {

    @Test
    void shouldCostOnlyTheQuantityItCovers() {
        CostBasis knownPart = CostBasis.of(
                Quantity.of(0.3), Price.of(100, "EUR"), Provenance.EXCHANGE_REPORTED);

        assertThat(knownPart.totalCost()).isEqualTo(Money.of(30, "EUR"));
    }

    @Test
    void shouldRecordCashAtParRatherThanImplyingIt() {
        CostBasis cash = CostBasis.atPar(Quantity.of(250), "EUR");

        assertThat(cash.provenance()).isEqualTo(Provenance.ASSUMED_PAR);
        assertThat(cash.avgPrice()).isEqualTo(Price.one("EUR"));
        assertThat(cash.totalCost()).isEqualTo(Money.of(250, "EUR"));
    }

    @Test
    void shouldAverageTwoKnownCostsByWeight() {
        CostBasis first = CostBasis.of(Quantity.of(1), Price.of(100, "EUR"), Provenance.DERIVED_FROM_FILLS);
        CostBasis second = CostBasis.of(Quantity.of(3), Price.of(200, "EUR"), Provenance.DERIVED_FROM_FILLS);

        CostBasis merged = first.merge(second);

        assertThat(merged.quantity()).isEqualTo(Quantity.of(4));
        assertThat(merged.avgPrice()).isEqualTo(Price.of(175, "EUR"));
        assertThat(merged.totalCost()).isEqualTo(Money.of(700, "EUR"));
    }

    /**
     * A merge must not quietly strip the protection a user's answer carries — otherwise one
     * exchange-reported number could erase what the person told us.
     */
    @Test
    void shouldKeepUserProvidedProtectionThroughAMerge() {
        CostBasis fromUser = CostBasis.of(Quantity.of(1), Price.of(100, "EUR"), Provenance.USER_PROVIDED);
        CostBasis fromExchange = CostBasis.of(Quantity.of(1), Price.of(200, "EUR"), Provenance.EXCHANGE_REPORTED);

        assertThat(fromUser.merge(fromExchange).provenance()).isEqualTo(Provenance.USER_PROVIDED);
        assertThat(fromExchange.merge(fromUser).provenance()).isEqualTo(Provenance.USER_PROVIDED);
    }

    @Test
    void shouldRefuseToMergeCostsInDifferentCurrencies() {
        CostBasis euro = CostBasis.of(Quantity.of(1), Price.of(100, "EUR"), Provenance.EXCHANGE_REPORTED);
        CostBasis dollar = CostBasis.of(Quantity.of(1), Price.of(100, "USD"), Provenance.EXCHANGE_REPORTED);

        assertThatThrownBy(() -> euro.merge(dollar))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageContaining("USD");
    }

    @Test
    void shouldKeepAveragePriceWhenPartOfThePositionIsSold() {
        CostBasis before = CostBasis.of(Quantity.of(4), Price.of(175, "EUR"), Provenance.DERIVED_FROM_FILLS);

        CostBasis after = before.reduceTo(Quantity.of(1));

        assertThat(after.avgPrice()).isEqualTo(Price.of(175, "EUR"));
        assertThat(after.totalCost()).isEqualTo(Money.of(175, "EUR"));
        assertThat(after.provenance()).isEqualTo(Provenance.DERIVED_FROM_FILLS);
    }

    @Test
    void shouldSayWhatMayBeOverwrittenSilently() {
        assertThat(Provenance.USER_PROVIDED.isOverwritableSilently()).isFalse();
        assertThat(Provenance.EXCHANGE_REPORTED.isOverwritableSilently()).isTrue();
        assertThat(Provenance.DERIVED_FROM_FILLS.isOverwritableSilently()).isTrue();
        assertThat(Provenance.ASSUMED_PAR.isOverwritableSilently()).isTrue();
    }

    @Test
    void shouldRejectIncompleteOrNegativeCost() {
        assertThatThrownBy(() -> CostBasis.of(null, Price.of(1, "EUR"), Provenance.ASSUMED_PAR))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("quantity");
        assertThatThrownBy(() -> CostBasis.of(Quantity.of(1), null, Provenance.ASSUMED_PAR))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("avgPrice");
        assertThatThrownBy(() -> CostBasis.of(Quantity.of(1), Price.of(1, "EUR"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("provenance");
        assertThatThrownBy(() -> CostBasis.of(Quantity.of(-1), Price.of(1, "EUR"), Provenance.ASSUMED_PAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }
}
