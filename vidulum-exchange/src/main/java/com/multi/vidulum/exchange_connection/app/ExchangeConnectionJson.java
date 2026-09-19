package com.multi.vidulum.exchange_connection.app;

import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;

import java.time.ZonedDateTime;

/**
 * Connection as the API returns it.
 *
 * <p>The two timestamps are separate fields on purpose. {@code lastSnapshotAt} says when the
 * account was last <i>read</i> from the exchange, {@code lastSyncAt} when what was read was last
 * <i>applied</i> to the portfolio, and they drift apart in both directions. Collapsing them into
 * one "updated at" would make balances from three days ago priced five seconds ago look fresh.
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
        ZonedDateTime lastSyncAt,
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
                connection.getLastSyncAt(),
                connection.getCreatedAt());
    }
}
