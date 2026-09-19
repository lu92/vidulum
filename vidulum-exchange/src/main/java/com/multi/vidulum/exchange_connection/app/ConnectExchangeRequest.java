package com.multi.vidulum.exchange_connection.app;

import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code POST /exchange-connection}.
 *
 * <p>The constraints here are what keeps a blank field out of the aggregate. {@code
 * ExchangeConnection} guards the same invariants with {@code IllegalArgumentException}, but that
 * is a last-resort assertion and would surface as a 500; Bean Validation turns the same mistake
 * into a 400 that names the offending field, through the handler already wired for
 * {@code MethodArgumentNotValidException}.
 */
public record ConnectExchangeRequest(

        @NotBlank(message = "broker is required")
        String broker,

        @NotBlank(message = "accountUid is required")
        String accountUid,

        @NotNull(message = "environment is required")
        ExchangeEnvironment environment,

        @NotBlank(message = "region is required")
        String region,

        @NotBlank(message = "reportedKeyPermissions is required")
        String reportedKeyPermissions,

        @NotBlank(message = "denominationCurrency is required")
        String denominationCurrency,

        /** Optional; defaults to {@link CredentialsMode#EXTERNAL}, which is the POC's mode. */
        CredentialsMode credentialsMode) {

    public ConnectExchangeCommand toCommand() {
        return new ConnectExchangeCommand(
                broker, accountUid, environment, region,
                reportedKeyPermissions, denominationCurrency, credentialsMode);
    }
}
