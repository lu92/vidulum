package com.multi.vidulum.okx;

/**
 * OKX serves different regions from different hosts — {@code eea.okx.com} against
 * {@code www.okx.com}, and the websocket hosts differ as well. A key issued in one region does
 * not authenticate against another, so the region belongs to the connection, not to config.
 *
 * <p>These values are <b>OKX's own split</b> and generalise to nothing: Binance divides its world
 * into Binance.US and Binance.com, Coinbase differently again. That is why the connection stores
 * the region as plain text and this enum stays in the OKX module — a type called
 * {@code ExchangeRegion} carrying these three constants would promise a shared vocabulary that
 * does not exist.
 */
public enum OkxRegion {
    EEA,
    GLOBAL,
    US
}
