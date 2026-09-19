package com.multi.vidulum.exchange_connection.app;

import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;

/**
 * What the caller must supply to register an exchange account.
 *
 * <p>Note what is absent: no API key, no secret, no passphrase. In the POC the backend holds no
 * credentials at all — {@code credentialsMode} records that as a decision rather than an
 * omission — and {@code reportedKeyPermissions} is what the caller claims the key may do.
 */
public record ConnectExchangeCommand(
        String broker,
        String accountUid,
        ExchangeEnvironment environment,
        String region,
        String reportedKeyPermissions,
        String denominationCurrency,
        CredentialsMode credentialsMode) {
}
