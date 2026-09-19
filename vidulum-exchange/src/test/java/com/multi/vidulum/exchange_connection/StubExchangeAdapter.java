package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.exchange_connection.domain.ExchangeAdapter;
import com.multi.vidulum.exchange_connection.domain.UnknownExchangeRegionException;

import java.util.List;
import java.util.Locale;

/**
 * Stands in for an exchange's module in this module's tests.
 *
 * <p>Deliberately not named after OKX: these tests must prove that the connection flow works for
 * <i>an</i> exchange, and this module cannot depend on any exchange's module anyway. The OKX
 * adapter has its own test next to the OKX code.
 */
class StubExchangeAdapter implements ExchangeAdapter {

    static final Broker DEMOEX = Broker.of("DEMOEX");
    static final List<String> REGIONS = List.of("EU", "US");

    @Override
    public Broker broker() {
        return DEMOEX;
    }

    @Override
    public void validateRegion(String region) {
        String candidate = region == null ? "" : region.trim().toUpperCase(Locale.ROOT);
        if (!REGIONS.contains(candidate)) {
            throw new UnknownExchangeRegionException(DEMOEX, region, REGIONS);
        }
    }
}
