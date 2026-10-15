package com.multi.vidulum.exchange_connection.app.commands.connect;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Registers an exchange account.
 *
 * <p>Note what is absent: no API key, no secret, no passphrase. In the POC the backend holds no
 * credentials at all — {@code credentialsMode} records that as a decision rather than an
 * omission — and {@code reportedKeyPermissions} is only what the caller claims the key may do.
 */
public record ConnectExchangeCommand(
        UserId userId,
        Broker broker,
        String accountUid,
        ExchangeEnvironment environment,
        String region,
        String reportedKeyPermissions,
        Currency denominationCurrency,
        CredentialsMode credentialsMode) implements Command {
}
