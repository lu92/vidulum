package com.multi.vidulum.exchange_connection.api.spi;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.exchange_connection.api.error.UnknownExchangeRegionException;

/**
 * What an exchange's own module has to provide before a connection to it can be created.
 *
 * <p>Deliberately small. Almost everything about a connection is the same everywhere — the
 * account id, the environment, the permissions, the valuation currency — and lives in
 * {@code ExchangeConnection}. The genuinely per-exchange part is the region vocabulary, which
 * does not generalise: OKX splits the world into {@code EEA}/{@code GLOBAL}/{@code US}, Binance
 * into {@code .com} and {@code .US}.
 *
 * <p>The set of registered adapters doubles as the answer to "which exchanges do we support",
 * which the service has to know anyway in order to reject an unknown broker.
 */
public interface ExchangeAdapter {

    /** Broker constant declared by the exchange's own module. */
    Broker broker();

    /**
     * @throws UnknownExchangeRegionException when the region is not one this exchange serves
     */
    void validateRegion(String region);
}
