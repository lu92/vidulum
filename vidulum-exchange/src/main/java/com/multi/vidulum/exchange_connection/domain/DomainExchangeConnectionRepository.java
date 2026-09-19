package com.multi.vidulum.exchange_connection.domain;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;

import java.util.List;
import java.util.Optional;

public interface DomainExchangeConnectionRepository {

    ExchangeConnection save(ExchangeConnection connection);

    Optional<ExchangeConnection> findById(ExchangeConnectionId id);

    /**
     * Lookup by natural key. This is the query the synchronisation engine runs before deciding
     * whether it is onboarding a new account or resuming a known one.
     */
    Optional<ExchangeConnection> findByAccount(
            UserId userId, Broker broker, ExchangeEnvironment environment, String accountUid);

    List<ExchangeConnection> findByUserId(UserId userId);

    /** Reverse direction: given a portfolio, which exchange account feeds it. */
    Optional<ExchangeConnection> findByPortfolioId(PortfolioId portfolioId);

    /**
     * A connection is visible only to the user who owns it.
     *
     * <p>Someone else's connection answers {@code not found} rather than {@code forbidden},
     * because a {@code 403} would confirm that the identifier exists. Kept here so every handler
     * enforces it the same way.
     */
    default ExchangeConnection findOwnedOrThrow(UserId userId, ExchangeConnectionId id) {
        return findById(id)
                .filter(connection -> connection.getUserId().equals(userId))
                .orElseThrow(() -> new ExchangeConnectionNotFoundException(id));
    }
}
