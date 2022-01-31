package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.OrderStatus;
import com.multi.vidulum.common.OriginOrderId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import com.multi.vidulum.shared.ddd.event.StoredDomainEvent;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

@Component
@AllArgsConstructor
public class DomainOrderRepositoryImpl implements DomainOrderRepository {

    private final OrderMongoRepository repository;
    private final Map<OrderId, List<StoredDomainEvent>> eventStore = new ConcurrentHashMap<>();
    private final Clock clock;

    @Override
    public Optional<Order> findById(OrderId orderId) {
        return repository.findById(orderId.getId())
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
                })
                .collect(Collectors.toList());

        eventStore.merge(aggregate.getOrderId(), uncommittedEvents, (domainEvents, domainEvents2) -> {
            domainEvents.addAll(domainEvents2);
            return domainEvents;
        });

        return Order.from(
                repository.save(OrderEntity.fromSnapshot(aggregate.getSnapshot()))
                        .toSnapshot());
    }

    @Override
    public List<Order> findOpenedOrdersForPortfolio(PortfolioId portfolioId) {
        return repository.findByPortfolioIdAndStatus(portfolioId.getId(), OrderStatus.OPEN)
                .stream()
                .map(OrderEntity::toSnapshot)
                .map(Order::from)
                .collect(toList());
    }

    @Override
    public Optional<Order> findByOriginOrderId(OriginOrderId originOrderId) {
        return repository.findByOriginOrderId(originOrderId.getId())
                .map(OrderEntity::toSnapshot)
                .map(Order::from);
    }

    @Override
    public List<DomainEvent> findDomainEvents(OrderId orderId) {
        return eventStore.getOrDefault(orderId, List.of()).stream()
                .map(StoredDomainEvent::event)
                .collect(Collectors.toList());
    }
}
