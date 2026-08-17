package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.infrastructure.entity.CashFlowEntity;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import com.multi.vidulum.shared.ddd.event.StoredDomainEvent;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link DomainCashFlowRepository} for unit tests.
 *
 * <p>Uses {@link CashFlowEntity} for snapshot round-trip serialization
 * (fromSnapshot → toSnapshot) to verify that entity mapping is correct,
 * without requiring a MongoDB container.</p>
 */
class InMemoryCashFlowRepository implements DomainCashFlowRepository {

    private final Map<String, CashFlowEntity> store = new ConcurrentHashMap<>();
    private final Map<CashFlowId, List<StoredDomainEvent>> eventStore = new ConcurrentHashMap<>();
    private final Clock clock;

    InMemoryCashFlowRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<CashFlow> findById(CashFlowId id) {
        return Optional.ofNullable(store.get(id.id()))
                .map(CashFlowEntity::toSnapshot)
                .map(CashFlow::from);
    }

    @Override
    public CashFlow save(CashFlow aggregate) {
        List<StoredDomainEvent> uncommittedEvents = aggregate.getUncommittedEvents().stream()
                .map(event -> new StoredDomainEvent() {
                    @Override
                    public String index() {
                        return aggregate.getSnapshot().cashFlowId().id();
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

        eventStore.merge(aggregate.getSnapshot().cashFlowId(), uncommittedEvents, (existing, incoming) -> {
            existing.addAll(incoming);
            return existing;
        });

        CashFlowEntity entity = CashFlowEntity.fromSnapshot(aggregate.getSnapshot());
        store.put(entity.getCashFlowId(), entity);

        // Round-trip through entity to mirror real repo behavior
        return CashFlow.from(entity.toSnapshot());
    }

    @Override
    public List<DomainEvent> findDomainEvents(CashFlowId cashFlowId) {
        return eventStore.getOrDefault(cashFlowId, List.of()).stream()
                .map(StoredDomainEvent::event)
                .collect(Collectors.toList());
    }

    @Override
    public List<CashFlow> findDetailsByUserId(UserId userId) {
        return store.values().stream()
                .filter(e -> e.getUserId().equals(userId.getId()))
                .map(CashFlowEntity::toSnapshot)
                .map(CashFlow::from)
                .toList();
    }

    @Override
    public List<CashFlow> findOpenCashFlowsNeedingRollover(YearMonth targetPeriod) {
        return store.values().stream()
                .filter(e -> e.getStatus() == CashFlow.CashFlowStatus.OPEN)
                .filter(e -> YearMonth.parse(e.getActivePeriod()).isBefore(targetPeriod))
                .map(CashFlowEntity::toSnapshot)
                .map(CashFlow::from)
                .toList();
    }

    @Override
    public boolean existsByUserIdAndName(UserId userId, String name) {
        return store.values().stream()
                .anyMatch(e -> e.getUserId().equals(userId.getId()) && e.getName().equals(name));
    }
}
