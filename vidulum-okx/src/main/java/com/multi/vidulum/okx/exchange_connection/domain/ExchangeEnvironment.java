package com.multi.vidulum.okx.exchange_connection.domain;

/**
 * Demo and live are separate accounts with separate balances and separate {@code uid} values,
 * so they are separate connections — and part of the natural key.
 */
public enum ExchangeEnvironment {
    DEMO,
    LIVE
}
