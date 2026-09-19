package com.multi.vidulum.okx.exchange_connection.domain;

/**
 * Which exchange a connection points at.
 *
 * <p>An enum rather than a value object like {@code Broker}: the set is closed and owned by us,
 * so nothing is gained by accepting arbitrary strings, and the natural key in
 * {@link ExchangeConnection} is safer when this cannot hold {@code "okx"} and {@code "OKX"} at
 * the same time.
 */
public enum Exchange {
    OKX
}
