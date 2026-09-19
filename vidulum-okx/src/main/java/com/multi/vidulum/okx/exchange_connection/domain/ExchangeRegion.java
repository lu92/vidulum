package com.multi.vidulum.okx.exchange_connection.domain;

/**
 * OKX serves different regions from different hosts — {@code eea.okx.com} against
 * {@code www.okx.com}, and the websocket hosts differ as well. A key issued in one region does
 * not authenticate against another, so the region belongs to the connection, not to config.
 */
public enum ExchangeRegion {
    EEA,
    GLOBAL,
    US
}
