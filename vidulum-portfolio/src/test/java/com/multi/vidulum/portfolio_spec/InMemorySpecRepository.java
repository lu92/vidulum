package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.DomainPortfolioSpecRepository;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.portfolio_spec.infrastructure.PortfolioSpecEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link DomainPortfolioSpecRepository} for component tests, following
 * {@code InMemoryPortfolioRepository}: state is stored as {@link PortfolioSpecEntity} and every
 * read goes back through {@code toDomain}, so a mapping that drops a field fails the flow tests
 * rather than only the mapping test.
 */
class InMemorySpecRepository implements DomainPortfolioSpecRepository {

    private final Map<String, PortfolioSpecEntity> store = new ConcurrentHashMap<>();

    @Override
    public PortfolioSpec save(PortfolioSpec spec) {
        PortfolioSpecEntity entity = PortfolioSpecEntity.from(spec);
        store.put(entity.getId(), entity);
        return entity.toDomain();
    }

    @Override
    public Optional<PortfolioSpec> findById(PortfolioSpecId id) {
        return Optional.ofNullable(store.get(id.getId())).map(PortfolioSpecEntity::toDomain);
    }

    @Override
    public List<PortfolioSpec> findByUserId(UserId userId) {
        return store.values().stream()
                .filter(entity -> entity.getUserId().equals(userId.getId()))
                .map(PortfolioSpecEntity::toDomain)
                .toList();
    }

    /** What was stored, read back once. */
    PortfolioSpec stored(String id) {
        return store.get(id).toDomain();
    }

    /** The same document mapped out and back in, to prove the mapping loses nothing. */
    PortfolioSpec roundTripped(String id) {
        return PortfolioSpecEntity.from(store.get(id).toDomain()).toDomain();
    }
}
