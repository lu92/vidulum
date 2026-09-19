package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory connection store, so the portfolio-spec tests can watch the connection change
 * state without a Mongo container. The exchange module has its own, richer stub; duplicating a
 * small one here keeps wealth's tests from depending on another module's test sources.
 */
class InMemoryExchangeConnectionRepositoryForSpec implements DomainExchangeConnectionRepository {

    private final Map<String, ExchangeConnection> store = new ConcurrentHashMap<>();

    @Override
    public ExchangeConnection save(ExchangeConnection connection) {
        store.put(connection.getId().getId(), connection);
        return connection;
    }

    @Override
    public Optional<ExchangeConnection> findById(ExchangeConnectionId id) {
        return Optional.ofNullable(store.get(id.getId()));
    }

    @Override
    public Optional<ExchangeConnection> findByAccount(
            UserId userId, Broker broker, ExchangeEnvironment environment, String accountUid) {
        return store.values().stream()
                .filter(connection -> connection.getUserId().equals(userId)
                        && connection.getBroker().equals(broker)
                        && connection.getEnvironment() == environment
                        && connection.getAccountUid().equals(accountUid))
                .findFirst();
    }

    @Override
    public List<ExchangeConnection> findByUserId(UserId userId) {
        return store.values().stream()
                .filter(connection -> connection.getUserId().equals(userId))
                .toList();
    }

    @Override
    public Optional<ExchangeConnection> findByPortfolioId(PortfolioId portfolioId) {
        return store.values().stream()
                .filter(connection -> Objects.equals(connection.getPortfolioId(), portfolioId))
                .findFirst();
    }
}
