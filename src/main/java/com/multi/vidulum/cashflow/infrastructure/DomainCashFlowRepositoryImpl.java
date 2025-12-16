package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.infrastructure.entity.CashFlowEntity;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import com.multi.vidulum.shared.ddd.event.StoredDomainEvent;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class DomainCashFlowRepositoryImpl implements DomainCashFlowRepository {

    private final CashFlowMongoRepository cashFlowMongoRepository;
    private final Map<CashFlowId, List<StoredDomainEvent>> eventStore = new ConcurrentHashMap<>();
    private final Clock clock;

    @Override
    public Optional<CashFlow> findById(CashFlowId id) {
        return cashFlowMongoRepository.findByCashFlowId(id.id())
                .map(CashFlowEntity::toSnapshot)
                .map(CashFlow::from);
    }

    @Override
    public CashFlow save(CashFlow aggregate) {
        List<StoredDomainEvent> uncommittedEvents = aggregate.getUncommittedEvents().stream()
                .map(cashChangeEvent -> new StoredDomainEvent() {
                    @Override
                    public String index() {
                        return aggregate.getSnapshot().cashFlowId().id();
                    }

                    @Override
                    public DomainEvent event() {
                        return cashChangeEvent;
                    }

                    @Override
                    public Instant occurredOn() {
                        return Instant.now(clock);
                    }
                }).collect(Collectors.toList());

        eventStore.merge(aggregate.getSnapshot().cashFlowId(), uncommittedEvents, (domainEvents, domainEvents2) -> {
            domainEvents.addAll(domainEvents2);
            return domainEvents;
        });

        CashFlowEntity rawCashFlowEntity = CashFlowEntity.fromSnapshot(aggregate.getSnapshot());
        CashFlowEntity savedCashFlowEntity = cashFlowMongoRepository.save(rawCashFlowEntity);
        return CashFlow.from(savedCashFlowEntity.toSnapshot());
    }

    @Override
    public List<DomainEvent> findDomainEvents(CashFlowId cashFlowId) {
        return eventStore.getOrDefault(cashFlowId, List.of()).stream()
                .map(StoredDomainEvent::event)
                .collect(Collectors.toList());
    }

    @Override
    public List<CashFlow> findDetailsByUserId(UserId userId) {
        return List.of();
    }

}
