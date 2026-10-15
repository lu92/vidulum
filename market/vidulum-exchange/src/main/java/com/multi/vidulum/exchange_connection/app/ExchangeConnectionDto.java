package com.multi.vidulum.exchange_connection.app;

import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionsOfUserQueryHandler;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Every JSON shape the exchange-connection endpoints accept or return, in one place — the layout
 * {@code CashFlowDto} uses.
 *
 * <p>Records rather than mutable beans: they give the all-args constructor {@code CLAUDE.md}
 * requires for test fixtures, and adding a field then breaks compilation in every test that
 * builds one, instead of passing silently.
 */
public final class ExchangeConnectionDto {

    private ExchangeConnectionDto() {
    }

    /**
     * Request body of {@code POST /exchange-connection}.
     *
     * <p>The constraints here are what keeps a blank field out of the aggregate.
     * {@code ExchangeConnection} guards the same invariants with {@code IllegalArgumentException},
     * but that is a last-resort assertion and would surface as a 500; bean validation turns the
     * same mistake into a 400 naming the offending field.
     */
    public record ConnectExchangeJson(

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
    }

    /** Request body of {@code POST /exchange-connection/{id}/revoke}. */
    public record RevokeConnectionJson(
            @NotBlank(message = "reason is required")
            String reason) {
    }

    /**
     * Connection as the API returns it.
     *
     * <p>Three timestamps, separate on purpose. {@code lastSnapshotAt} says when the account was
     * last <i>read</i> from the exchange; {@code portfolioSyncedAt} when what was read was last
     * <i>applied</i> to the portfolio; {@code statusChangedAt} when the connection last changed
     * state. They drift apart in every direction, and collapsing them into one "updated at" would
     * make balances from three days ago priced five seconds ago look fresh.
     *
     * <p>{@code portfolioSyncedAt} is the one a reader should judge freshness by, and it is
     * {@code null} until a snapshot has actually been applied. It used to be called
     * {@code lastSyncAt} and was overwritten by every status transition, so a resumed connection
     * reported itself synchronised the moment it came back (task H1).
     */
    public record ExchangeConnectionJson(
            String id,
            String userId,
            String broker,
            String accountUid,
            String environment,
            String region,
            String reportedKeyPermissions,
            String credentialsMode,
            String denominationCurrency,
            String portfolioId,
            String status,
            String statusReason,
            ZonedDateTime lastSnapshotAt,
            ZonedDateTime portfolioSyncedAt,
            ZonedDateTime statusChangedAt,
            ZonedDateTime createdAt) {

        public static ExchangeConnectionJson from(ExchangeConnection connection) {
            return new ExchangeConnectionJson(
                    connection.getId().getId(),
                    connection.getUserId().getId(),
                    connection.getBroker().getId(),
                    connection.getAccountUid(),
                    connection.getEnvironment().name(),
                    connection.getRegion(),
                    connection.getReportedKeyPermissions().getRaw(),
                    connection.getCredentialsMode().name(),
                    connection.getDenominationCurrency().getId(),
                    connection.getPortfolioId() != null ? connection.getPortfolioId().getId() : null,
                    connection.getStatus().name(),
                    connection.getStatusReason(),
                    connection.getLastSnapshotAt(),
                    connection.getPortfolioSyncedAt(),
                    connection.getStatusChangedAt(),
                    connection.getCreatedAt());
        }
    }

    /** Every connection the caller owns, plus the exchanges that can be connected at all. */
    public record ExchangeConnectionsListJson(
            List<ExchangeConnectionJson> connections,
            List<String> supportedExchanges) {

        public static ExchangeConnectionsListJson from(
                GetExchangeConnectionsOfUserQueryHandler.Result result) {
            return new ExchangeConnectionsListJson(
                    result.connections().stream().map(ExchangeConnectionJson::from).toList(),
                    result.supportedExchanges());
        }
    }
}
