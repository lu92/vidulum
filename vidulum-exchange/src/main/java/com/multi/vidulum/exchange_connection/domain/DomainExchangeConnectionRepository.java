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
}
