package com.multi.vidulum.exchange_connection.domain;

import com.multi.vidulum.common.Broker;
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
 * <p><b>Natural key.</b> {@code (userId, broker, environment, accountUid)} identifies the
 * account. Exchanges hand their account id back on the first authenticated call — OKX returns
 * {@code uid} from {@code GET /account/config} — so it costs nothing to record, and without it
 * the same account can be connected twice — producing two portfolios
 * holding the same assets. The key is scoped to the user on purpose: a globally unique
 * {@code accountUid} would let one account lock every other user out of connecting it, and
 * portfolios are per-user anyway, so nothing is double counted across users.
 *
 * <p><b>No state is terminal.</b> See {@link ConnectionStatus}.
 *
 * <p>This class knows nothing about any particular exchange — that is the point of the module it
 * lives in. Per-exchange details (region vocabulary, REST hosts, websocket channels) belong to
 * the exchange's own module.
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

    /**
     * Which exchange this is. Reuses {@link Broker} from shared-kernel rather than a private
     * enum so the connection and the portfolio it feeds name the exchange with the same value —
     * {@code Portfolio.broker} is a {@link Broker} too. A private enum would also force every
     * new exchange to edit this module; a broker constant is declared by the exchange's own
     * module, exactly as {@code OkxBrokerQuotationProvider.OKX} does today.
     *
     * <p>The id is upper-cased on the way in, because it is part of the natural key and
     * {@code "okx"} must not become a second account alongside {@code "OKX"}.
     */
    private final Broker broker;

    /** {@code uid} reported by the exchange. Part of the natural key. */
    private final String accountUid;

    private final ExchangeEnvironment environment;

    /**
     * Opaque, exchange-specific routing hint — {@code "EEA"}, {@code "GLOBAL"}, {@code "US"} for
     * OKX, something else for the next exchange. Stored as text because there is no shared
     * vocabulary to model: the connection only carries it, and the exchange's own module
     * interprets it (see {@code OkxRegion}) and validates it before calling {@link #pending}.
     */
    private final String region;

    /**
     * Permissions the API key reports — {@code perm} from {@code GET /account/config}.
     *
     * <p>The {@code reported} prefix is deliberate: in the POC the backend holds no credentials,
     * so this value arrives from the caller and is only as trustworthy as the caller. The prefix
     * drops once the backend fetches it itself. {@link ReportedKeyPermissions} refuses anything
     * broader than {@code read_only}, so this field can only ever hold a read-only key —
     * for every exchange, not only OKX.
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
            Broker broker,
            String accountUid,
            ExchangeEnvironment environment,
            String region,
            ReportedKeyPermissions reportedKeyPermissions,
            CredentialsMode credentialsMode,
            Currency denominationCurrency,
            ZonedDateTime now) {

        requireText(accountUid, "accountUid");
        requireText(broker != null ? broker.getId() : null, "broker id");
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(broker, "broker is required");
        Objects.requireNonNull(environment, "environment is required");
        requireText(region, "region");
        Objects.requireNonNull(reportedKeyPermissions, "reportedKeyPermissions is required");
        Objects.requireNonNull(credentialsMode, "credentialsMode is required");
        Objects.requireNonNull(denominationCurrency, "denominationCurrency is required");
        Objects.requireNonNull(now, "now is required");

        return ExchangeConnection.builder()
                .id(id)
                .userId(userId)
                .broker(normalised(broker))
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

    /** Upper-cases the broker id so the natural key cannot split on letter case. */
    private static Broker normalised(Broker broker) {
        return Broker.of(broker.getId().trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
