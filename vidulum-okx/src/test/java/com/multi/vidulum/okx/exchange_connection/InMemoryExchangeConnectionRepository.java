package com.multi.vidulum.okx.exchange_connection;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.okx.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.okx.exchange_connection.domain.Exchange;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.okx.exchange_connection.infrastructure.ExchangeConnectionEntity;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link DomainExchangeConnectionRepository} for component tests, following
 * {@code InMemoryPortfolioRepository}: state is stored as {@link ExchangeConnectionEntity} and
 * every read goes back through {@code toDomain}, so a mapping that drops a field fails the flow
 * tests rather than only the mapping test.
 *
 * <p>Two behaviours are copied from the real store on purpose, because otherwise a component test
 * would pass against a stub that is more permissive than production:
 *
 * <ul>
 *   <li>the natural key {@code (userId, exchange, environment, accountUid)} is unique, and a
 *       second connection under the same key raises {@link DuplicateKeyException} — the same type
 *       MongoDB's unique index produces;
 *   <li>saving an existing id replaces that record instead of adding one.
 * </ul>
 */
class InMemoryExchangeConnectionRepository implements DomainExchangeConnectionRepository {

    private final Map<String, ExchangeConnectionEntity> store = new ConcurrentHashMap<>();

    @Override
    public ExchangeConnection save(ExchangeConnection connection) {
        ExchangeConnectionEntity entity = ExchangeConnectionEntity.from(connection);
        store.values().stream()
                .filter(existing -> !existing.getId().equals(entity.getId()))
                .filter(existing -> sharesNaturalKey(existing, entity))
                .findAny()
                .ifPresent(clash -> {
                    throw new DuplicateKeyException(
                            "duplicate natural key, already held by [" + clash.getId() + "]");
                });

        store.put(entity.getId(), entity);
        return entity.toDomain();
    }

    @Override
    public Optional<ExchangeConnection> findById(ExchangeConnectionId id) {
        return Optional.ofNullable(store.get(id.getId())).map(ExchangeConnectionEntity::toDomain);
    }

    @Override
    public Optional<ExchangeConnection> findByAccount(
            UserId userId, Exchange exchange, ExchangeEnvironment environment, String accountUid) {
        return store.values().stream()
                .filter(entity -> entity.getUserId().equals(userId.getId()))
                .filter(entity -> entity.getExchange().equals(exchange.name()))
                .filter(entity -> entity.getEnvironment().equals(environment.name()))
                .filter(entity -> entity.getAccountUid().equals(accountUid))
                .findFirst()
                .map(ExchangeConnectionEntity::toDomain);
    }

    @Override
    public List<ExchangeConnection> findByUserId(UserId userId) {
        return store.values().stream()
                .filter(entity -> entity.getUserId().equals(userId.getId()))
                .map(ExchangeConnectionEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<ExchangeConnection> findByPortfolioId(PortfolioId portfolioId) {
        return store.values().stream()
                .filter(entity -> Objects.equals(entity.getPortfolioId(), portfolioId.getId()))
                .findFirst()
                .map(ExchangeConnectionEntity::toDomain);
    }

    int size() {
        return store.size();
    }

    private static boolean sharesNaturalKey(
            ExchangeConnectionEntity left, ExchangeConnectionEntity right) {
        return left.getUserId().equals(right.getUserId())
                && left.getExchange().equals(right.getExchange())
                && left.getEnvironment().equals(right.getEnvironment())
                && left.getAccountUid().equals(right.getAccountUid());
    }
}
