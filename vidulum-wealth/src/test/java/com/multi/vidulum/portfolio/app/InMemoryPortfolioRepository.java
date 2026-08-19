package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import com.multi.vidulum.shared.ddd.event.StoredDomainEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link DomainPortfolioRepository} for unit tests.
 * Uses {@link PortfolioEntity} for snapshot round-trip (fromSnapshot/toSnapshot).
 */
class InMemoryPortfolioRepository implements DomainPortfolioRepository {

    private final Map<String, PortfolioEntity> store = new ConcurrentHashMap<>();
    private final Map<PortfolioId, List<StoredDomainEvent>> eventStore = new ConcurrentHashMap<>();
    private final Clock clock;

    InMemoryPortfolioRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<Portfolio> findById(PortfolioId portfolioId) {
        return Optional.ofNullable(store.get(portfolioId.getId()))
                .map(PortfolioEntity::toSnapshot)
                .map(Portfolio::from);
    }

    @Override
    public Portfolio save(Portfolio aggregate) {
        List<StoredDomainEvent> uncommittedEvents = aggregate.getUncommittedEvents().stream()
                .map(event -> new StoredDomainEvent() {
                    @Override
                    public String index() {
                        return aggregate.getPortfolioId().getId();
                    }

                    @Override
                    public DomainEvent event() {
                        return event;
                    }

                    @Override
                    public Instant occurredOn() {
                        return Instant.now(clock);
                    }
                }).collect(Collectors.toList());

        eventStore.merge(aggregate.getPortfolioId(), uncommittedEvents, (existing, incoming) -> {
            existing.addAll(incoming);
            return existing;
        });

        PortfolioEntity entity = PortfolioEntity.fromSnapshot(aggregate.getSnapshot());
        store.put(entity.getPortfolioId(), entity);
        return Portfolio.from(entity.toSnapshot());
    }

    @Override
    public List<Portfolio> findByUserId(UserId userId) {
        return store.values().stream()
                .filter(e -> e.getUserId().equals(userId.getId()))
                .map(PortfolioEntity::toSnapshot)
                .map(Portfolio::from)
                .toList();
    }

    @Override
    public List<DomainEvent> findDomainEvents(PortfolioId portfolioId) {
        return eventStore.getOrDefault(portfolioId, List.of()).stream()
                .map(StoredDomainEvent::event)
                .collect(Collectors.toList());
    }
}
