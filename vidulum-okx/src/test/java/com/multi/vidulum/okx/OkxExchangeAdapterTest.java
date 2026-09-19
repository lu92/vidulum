package com.multi.vidulum.okx;

import com.multi.vidulum.exchange_connection.domain.UnknownExchangeRegionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What OKX contributes to onboarding: its identity and its region vocabulary. Everything else in
 * the flow is exchange-agnostic and tested in vidulum-exchange.
 */
class OkxExchangeAdapterTest {

    private final OkxExchangeAdapter adapter = new OkxExchangeAdapter();

    @Test
    void shouldRegisterUnderTheSameBrokerUsedForQuotations() {
        assertThat(adapter.broker()).isEqualTo(OkxBrokerQuotationProvider.OKX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EEA", "GLOBAL", "US", "eea", "  Global  "})
    void shouldAcceptEveryRegionOkxServes(String region) {
        assertThatCode(() -> adapter.validateRegion(region)).doesNotThrowAnyException();
    }

    /**
     * A key issued for one OKX region does not authenticate against another, so an unrecognised
     * region has to be refused at registration rather than discovered later as a 401 from the
     * exchange.
     */
    @ParameterizedTest
    @ValueSource(strings = {"MARS", "EU", "", "   "})
    void shouldRefuseRegionOkxDoesNotServe(String region) {
        assertThatThrownBy(() -> adapter.validateRegion(region))
                .isInstanceOf(UnknownExchangeRegionException.class)
                .hasMessageContaining("OKX");
    }

    @Test
    void shouldRefuseNullRegion() {
        assertThatThrownBy(() -> adapter.validateRegion(null))
                .isInstanceOf(UnknownExchangeRegionException.class);
    }

    @Test
    void shouldListTheAcceptedRegionsSoTheCallerCanCorrectThem() {
        assertThatThrownBy(() -> adapter.validateRegion("MARS"))
                .hasMessageContaining("EEA")
                .hasMessageContaining("GLOBAL")
                .hasMessageContaining("US");
    }
}
