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
import com.multi.vidulum.exchange_connection.api.ConnectionStatus;
import com.multi.vidulum.exchange_connection.api.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.api.error.IllegalConnectionTransitionException;

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

    /**
     * When the portfolio was last brought into agreement with the exchange (task H1).
     *
     * <p>One meaning, and only {@link #recordSync} writes it. The field it replaces was called
     * {@code lastSyncAt} and claimed exactly this, while {@code confirm}, {@code reconnect},
     * {@code revoke} <b>and</b> {@code fail} all overwrote it — so resuming a connection recorded
     * "synchronised just now" over a portfolio holding week-old numbers, and a failure recorded it
     * too. Nothing in production ever called {@code recordSync}, so the one event the field is
     * named after was the only one that never touched it.
     *
     * <p>{@code null} until a snapshot has actually been applied. That is a real state — a
     * connection can be {@code ACTIVE} with a portfolio and no synchronisation behind it — and it
     * is the anchor the gap replay will read (H5), which is why it must not be guessed.
     */
    private ZonedDateTime portfolioSyncedAt;

    /**
     * When the status last changed. What {@code lastSyncAt} actually held, under its own name.
     *
     * <p>Kept because it answers a real question — how long has this been revoked — which is a
     * different question from how old the numbers are.
     */
    private ZonedDateTime statusChangedAt;

    private final ZonedDateTime createdAt;

    /**
     * What storage said this connection's version was when it was read (task F57). The domain does
     * not use it - it travels so the repository can make the next save conditional. A connection is
     * read-modify-write all through its life: revoke, reconnect, and the portfolio id written onto
     * it when a specification is confirmed.
     */
    private Long version;

    /**
     * A connection that has been described but not yet confirmed. No portfolio is attached and
     * nothing has been read from the exchange.
     */
    /**
     * Storage telling this object which version it now is (task F57).
     *
     * <p>Without it, a flow that saves the same instance twice - and there are such flows - sends a
     * version of null on the second save, which Spring Data reads as "new document": the update
     * becomes an insert and Mongo answers {@code DuplicateKeyException} on the id. Keeping the
     * instance in step with storage is safer than asking every caller to use what {@code save}
     * returned, because a caller can forget.
     */
    public void applyStoredVersion(Long version) {
        this.version = version;
    }

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
        this.statusChangedAt = now;
        // The one status transition that is also a synchronisation: a portfolio is born from the
        // snapshot being confirmed, so at this instant the two genuinely agree.
        this.portfolioSyncedAt = now;
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
        this.statusChangedAt = now;
        // Deliberately does NOT touch portfolioSyncedAt. Coming back into service says nothing
        // about the numbers: they are exactly as old as they were during the break, and claiming
        // otherwise is what made ACTIVE a status that could not be trusted (task H1).
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
        this.statusChangedAt = now;
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
        this.statusChangedAt = now;
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

    /**
     * A snapshot was applied to the portfolio, so the two now agree (task H1).
     *
     * <p>The only writer of {@link #portfolioSyncedAt}, and called from where a specification is
     * actually confirmed — including the case where there was nothing to apply, because "already
     * in agreement" is agreement. Status is untouched: bringing the numbers up to date is not a
     * transition.
     */
    public void recordSync(ZonedDateTime now) {
        this.portfolioSyncedAt = now;
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
