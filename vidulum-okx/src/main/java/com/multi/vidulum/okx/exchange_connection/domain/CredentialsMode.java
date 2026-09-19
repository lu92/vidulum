package com.multi.vidulum.okx.exchange_connection.domain;

/**
 * Where the API credentials live.
 *
 * <p>{@link #EXTERNAL} records that Vidulum holds none — the POC script keeps them and calls the
 * exchange itself. This is a deliberate state, not a missing feature: without the field, the
 * absence of encrypted storage reads as an oversight.
 */
public enum CredentialsMode {

    /** POC — keys stay in the caller, backend never sees them. */
    EXTERNAL,

    /** Target — keys stored encrypted, backend calls the exchange on its own. */
    STORED_ENCRYPTED
}
