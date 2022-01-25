package com.multi.vidulum.portfolio.infrastructure.portfolio;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.portfolio.infrastructure.portfolio.entities.PortfolioEntity;
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
public class DomainPortfolioRepositoryImpl implements DomainPortfolioRepository {

    private final PortfolioMongoRepository portfolioMongoRepository;
    private final Map<PortfolioId, List<StoredDomainEvent>> eventStore = new ConcurrentHashMap<>();
    private final Clock clock;

    @Override
    public Optional<Portfolio> findById(PortfolioId portfolioId) {
        return portfolioMongoRepository.findByPortfolioId(portfolioId.getId())
                .map(PortfolioEntity::toSnapshot)
                .map(Portfolio::from);
    }

    @Override
    public Portfolio save(Portfolio aggregate) {
//        eventStore.putIfAbsent(aggregate.getPortfolioId(), aggregate.getUncommittedEvents());

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
                })
                .collect(Collectors.toList());

        eventStore.merge(aggregate.getPortfolioId(), uncommittedEvents, (domainEvents, domainEvents2) -> {
            domainEvents.addAll(domainEvents2);
            return domainEvents;
        });
        return Portfolio.from(
                portfolioMongoRepository.save(PortfolioEntity.fromSnapshot(aggregate.getSnapshot()))
                        .toSnapshot());
    }

    @Override
    public List<Portfolio> findByUserId(UserId userId) {
        return portfolioMongoRepository.findByUserId(userId.getId()).stream()
                .map(PortfolioEntity::toSnapshot)
                .map(Portfolio::from)
                .collect(Collectors.toList());
    }

    @Override
    public List<DomainEvent> findDomainEvents(PortfolioId portfolioId) {
        return eventStore.getOrDefault(portfolioId, List.of()).stream()
                .map(StoredDomainEvent::event)
                .collect(Collectors.toList());
    }
}
