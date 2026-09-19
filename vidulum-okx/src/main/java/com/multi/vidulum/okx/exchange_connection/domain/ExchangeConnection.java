package com.multi.vidulum.okx.exchange_connection.domain;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Link between a Vidulum user and one account on an exchange.
 *
 * <p>It exists so that the second synchronisation is not a first one. The engine behind
 * {@code PortfolioSpec} computes {@code snapshot - known state}; without a record of which
 * portfolio belongs to which exchange account, the known state is always empty and every
 * synchronisation looks like onboarding.
 *
 * <p><b>Natural key.</b> {@code (userId, exchange, environment, accountUid)} identifies the
 * account. OKX hands {@code uid} back from {@code GET /account/config}, so it costs nothing to
 * record, and without it the same account can be connected twice — producing two portfolios
 * holding the same assets. The key is scoped to the user on purpose: a globally unique
 * {@code accountUid} would let one account lock every other user out of connecting it, and
 * portfolios are per-user anyway, so nothing is double counted across users.
 *
 * <p><b>No state is terminal.</b> See {@link ConnectionStatus}.
 *
 * <p>Setters are absent deliberately — every field that changes over the connection's life does
 * so through a method that validates the transition first.
 */
@Getter
@Builder
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class ExchangeConnection {

    private final ExchangeConnectionId id;
    private final UserId userId;
    private final Exchange exchange;

    /** {@code uid} reported by the exchange. Part of the natural key. */
    private final String accountUid;

    private final ExchangeEnvironment environment;
    private final ExchangeRegion region;

    /**
     * Permissions the API key reports — {@code perm} from {@code GET /account/config}.
     *
     * <p>The {@code reported} prefix is deliberate: in the POC the backend holds no credentials,
     * so this value arrives from the caller and is only as trustworthy as the caller. The prefix
     * drops once the backend fetches it itself. {@link ReportedKeyPermissions} refuses anything
     * broader than {@code read_only}, so this field can only ever hold a read-only key.
     */
    private final ReportedKeyPermissions reportedKeyPermissions;

    private final CredentialsMode credentialsMode;

    /**
     * Valuation currency. An <b>input</b> to the connection, not a result of onboarding: quotes
     * are published against it and they must be in the cache before the portfolio is created,
     * so it has to be known before the first question is asked.
     */
    private final Currency denominationCurrency;

    /** Empty until {@link #confirm}. Cardinality is 1:1 — one connection, one portfolio. */
    private PortfolioId portfolioId;

    private ConnectionStatus status;

    /** Why the connection is in {@link ConnectionStatus#ERROR} or {@link ConnectionStatus#REVOKED}. */
    private String statusReason;

    /** When the account state was last read from the exchange. Basis for the spec TTL. */
    private ZonedDateTime lastSnapshotAt;

    /** When a snapshot was last applied to the portfolio. Drifts apart from the one above. */
    private ZonedDateTime lastSyncAt;

    private final ZonedDateTime createdAt;

    /**
     * A connection that has been described but not yet confirmed. No portfolio is attached and
     * nothing has been read from the exchange.
     */
    public static ExchangeConnection pending(
            ExchangeConnectionId id,
            UserId userId,
            Exchange exchange,
            String accountUid,
            ExchangeEnvironment environment,
            ExchangeRegion region,
            ReportedKeyPermissions reportedKeyPermissions,
            CredentialsMode credentialsMode,
            Currency denominationCurrency,
            ZonedDateTime now) {

        requireText(accountUid, "accountUid");
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(exchange, "exchange is required");
        Objects.requireNonNull(environment, "environment is required");
        Objects.requireNonNull(region, "region is required");
        Objects.requireNonNull(reportedKeyPermissions, "reportedKeyPermissions is required");
        Objects.requireNonNull(credentialsMode, "credentialsMode is required");
        Objects.requireNonNull(denominationCurrency, "denominationCurrency is required");
        Objects.requireNonNull(now, "now is required");

        return ExchangeConnection.builder()
                .id(id)
                .userId(userId)
                .exchange(exchange)
                .accountUid(accountUid)
                .environment(environment)
                .region(region)
                .reportedKeyPermissions(reportedKeyPermissions)
                .credentialsMode(credentialsMode)
                .denominationCurrency(denominationCurrency)
                .status(ConnectionStatus.PENDING)
                .createdAt(now)
                .build();
    }

    /**
     * Onboarding succeeded: the portfolio exists and the connection starts serving synchronisation.
     *
     * <p>Only from {@link ConnectionStatus#PENDING} — coming back after a break is
     * {@link #reconnect}, which must keep the portfolio it already has.
     */
    public void confirm(PortfolioId portfolioId, ZonedDateTime now) {
        Objects.requireNonNull(portfolioId, "portfolioId is required");
        requireStatus(ConnectionStatus.PENDING, "confirm");
        this.portfolioId = portfolioId;
        this.status = ConnectionStatus.ACTIVE;
        this.statusReason = null;
        this.lastSyncAt = now;
    }

    /**
     * The same exchange account came back after the connection was revoked.
     *
     * <p>Deliberately <b>not</b> onboarding: the portfolio from before the break is kept, so the
     * spec that follows is computed against a non-empty known state. A large gap makes the
     * difference bigger, not different in kind.
     */
    public void reconnect(ZonedDateTime now) {
        requireStatus(ConnectionStatus.REVOKED, "reconnect");
        if (portfolioId == null) {
            throw new IllegalConnectionTransitionException(
                    id, status, "reconnect without a portfolio — the connection was never confirmed");
        }
        this.status = ConnectionStatus.ACTIVE;
        this.statusReason = null;
        this.lastSyncAt = now;
    }

    /**
     * Contact with the exchange was lost — expired key, user disconnected, exchange answered 401.
     * Nothing is deleted: {@code accountUid} and {@code portfolioId} survive so that
     * {@link #reconnect} can pick the connection back up.
     */
    public void revoke(String reason, ZonedDateTime now) {
        requireStatus(ConnectionStatus.ACTIVE, "revoke");
        this.status = ConnectionStatus.REVOKED;
        this.statusReason = reason;
        this.lastSyncAt = now;
    }

    /**
     * The connection could not be established or used. Recoverable — a corrected key moves it
     * back to {@link ConnectionStatus#PENDING} through {@link #retry}.
     */
    public void fail(String reason, ZonedDateTime now) {
        if (status == ConnectionStatus.ERROR) {
            this.statusReason = reason;
            return;
        }
        requireAnyStatus("fail", ConnectionStatus.PENDING, ConnectionStatus.ACTIVE);
        this.status = ConnectionStatus.ERROR;
        this.statusReason = reason;
        this.lastSyncAt = now;
    }

    /** A corrected key after {@link #fail} — back to the start of onboarding. */
    public void retry() {
        requireStatus(ConnectionStatus.ERROR, "retry");
        this.status = ConnectionStatus.PENDING;
        this.statusReason = null;
    }

    /** State was read from the exchange. Says nothing about whether it was applied. */
    public void recordSnapshot(ZonedDateTime now) {
        this.lastSnapshotAt = now;
    }

    /** A snapshot was applied to the portfolio. */
    public void recordSync(ZonedDateTime now) {
        this.lastSyncAt = now;
    }

    public boolean isActive() {
        return status == ConnectionStatus.ACTIVE;
    }

    /** True once a portfolio exists, regardless of whether the connection is currently usable. */
    public boolean isOnboarded() {
        return portfolioId != null;
    }

    private void requireStatus(ConnectionStatus expected, String operation) {
        if (status != expected) {
            throw new IllegalConnectionTransitionException(id, status, operation);
        }
    }

    private void requireAnyStatus(String operation, ConnectionStatus... allowed) {
        for (ConnectionStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalConnectionTransitionException(id, status, operation);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
