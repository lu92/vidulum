package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.OrderStatus;
import com.multi.vidulum.common.OriginOrderId;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import com.multi.vidulum.shared.ddd.event.StoredDomainEvent;
import com.multi.vidulum.trading.infrastructure.OrderEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link DomainOrderRepository} for unit tests.
 * Uses {@link OrderEntity} for snapshot round-trip (fromSnapshot/toSnapshot).
 */
class InMemoryOrderRepository implements DomainOrderRepository {

    private final Map<String, OrderEntity> store = new ConcurrentHashMap<>();
    private final Map<OrderId, List<StoredDomainEvent>> eventStore = new ConcurrentHashMap<>();
    private final Clock clock;

    InMemoryOrderRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<Order> findById(OrderId orderId) {
        return Optional.ofNullable(store.get(orderId.getId()))
                .map(OrderEntity::toSnapshot)
                .map(Order::from);
    }

    @Override
    public Order save(Order aggregate) {
        List<StoredDomainEvent> uncommittedEvents = aggregate.getUncommittedEvents().stream()
                .map(event -> new StoredDomainEvent() {
                    @Override
                    public String index() {
                        return aggregate.getOrderId().getId();
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

        eventStore.merge(aggregate.getOrderId(), uncommittedEvents, (existing, incoming) -> {
            existing.addAll(incoming);
            return existing;
        });

        OrderEntity entity = OrderEntity.fromSnapshot(aggregate.getSnapshot());
        store.put(entity.getOrderId(), entity);
        return Order.from(entity.toSnapshot());
    }

    @Override
    public List<Order> findOpenedOrdersForPortfolio(PortfolioId portfolioId) {
        return store.values().stream()
                .map(OrderEntity::toSnapshot)
                .filter(s -> s.getPortfolioId().equals(portfolioId))
                .filter(s -> s.getState().status() == OrderStatus.OPEN)
                .map(Order::from)
                .toList();
    }

    @Override
    public Optional<Order> findByOriginOrderId(OriginOrderId originOrderId) {
        return store.values().stream()
                .map(OrderEntity::toSnapshot)
                .filter(s -> s.getOriginOrderId().equals(originOrderId))
                .findFirst()
                .map(Order::from);
    }

    @Override
    public List<DomainEvent> findDomainEvents(OrderId orderId) {
        return eventStore.getOrDefault(orderId, List.of()).stream()
                .map(StoredDomainEvent::event)
                .collect(Collectors.toList());
    }
}
