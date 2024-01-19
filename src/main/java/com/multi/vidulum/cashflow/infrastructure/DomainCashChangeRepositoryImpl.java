package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.cashflow.domain.CashChange;
import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.DomainCashChangeRepository;
import com.multi.vidulum.cashflow.infrastructure.entity.CashChangeEntity;
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
public class DomainCashChangeRepositoryImpl implements DomainCashChangeRepository {

    private final CashChangeMongoRepository cashChangeMongoRepository;
    private final Map<CashChangeId, List<StoredDomainEvent>> eventStore = new ConcurrentHashMap<>();
    private final Clock clock;

    @Override
    public Optional<CashChange> findById(CashChangeId id) {
        return cashChangeMongoRepository.findByCashChangeId(id.getId())
                .map(CashChangeEntity::toSnapshot)
                .map(CashChange::from);
    }

    @Override
    public CashChange save(CashChange aggregate) {
        List<StoredDomainEvent> uncommittedEvents = aggregate.getUncommittedEvents().stream()
                .map(cashChangeEvent -> new StoredDomainEvent() {
                    @Override
                    public String index() {
                        return aggregate.getSnapshot().cashChangeId().getId();
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

        eventStore.merge(aggregate.getSnapshot().cashChangeId(), uncommittedEvents, (domainEvents, domainEvents2) -> {
            domainEvents.addAll(domainEvents2);
            return domainEvents;
        });

        CashChangeEntity rawCashChangeEntity = CashChangeEntity.fromSnapshot(aggregate.getSnapshot());
        CashChangeEntity savedCashChangeEntity = cashChangeMongoRepository.save(rawCashChangeEntity);
        return CashChange.from(savedCashChangeEntity.toSnapshot());
    }

    @Override
    public List<DomainEvent> findDomainEvents(CashChangeId cashChangeId) {
        return eventStore.getOrDefault(cashChangeId, List.of()).stream()
                .map(StoredDomainEvent::event)
                .collect(Collectors.toList());
    }
}
