package com.multi.vidulum.exchange_connection.api;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.api.error.ExchangeConnectionNotFoundException;
import com.multi.vidulum.exchange_connection.api.view.ConnectionView;

import java.time.ZonedDateTime;

/**
 * What other modules may ask of an exchange connection.
 *
 * <p>Four methods, which is what {@code portfolio_spec} calls while it onboards an account and
 * resumes one after a break. Registering, revoking and listing connections all arrive over HTTP
 * from the owner and need no seat here.
 *
 * <p>Before this, onboarding reached into the exchange module directly: it injected
 * {@code ConfirmExchangeConnectionCommandHandler}, {@code ReconnectExchangeCommandHandler} and
 * {@code RecordPortfolioSyncCommandHandler} — three of its command handlers — and read connections
 * straight out of {@code DomainExchangeConnectionRepository}. Thirteen imports into another
 * module's internals, the same shape the portfolio and trading contracts have already removed.
 */
public interface ExchangeConnectionApi {

    /**
     * The caller's own connection.
     *
     * @throws ExchangeConnectionNotFoundException for somebody else's connection as well as a
     *         missing one — the caller may not learn which
     */
    ConnectionView ownedOrThrow(UserId owner, ExchangeConnectionId connectionId);

    /** Puts a connection into service against the portfolio onboarding has just created. */
    void confirm(UserId owner, ExchangeConnectionId connectionId, PortfolioId portfolioId);

    /** Resumes a revoked connection, keeping the portfolio it already serves. */
    void reconnect(UserId owner, ExchangeConnectionId connectionId);

    /** Records that the portfolio behind this connection has been brought up to date. */
    void recordSync(UserId owner, ExchangeConnectionId connectionId, ZonedDateTime syncedAt);
}
