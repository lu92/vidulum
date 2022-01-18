package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.PortfolioEvents;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.time.Instant;
import java.util.LinkedList;
import java.util.List;

public class PortfolioFactory {

    public Portfolio empty(PortfolioId portfolioId, String name, UserId userId, Broker broker) {
        List<DomainEvent> uncommittedEvents = new LinkedList<>();
        uncommittedEvents.add(
                new PortfolioEvents.PortfolioOpenedEvent(
                        portfolioId,
                        name,
                        broker,
                        Instant.now()
                )
        );
        return Portfolio.builder()
                .portfolioId(portfolioId)
                .userId(userId)
                .name(name)
                .broker(broker)
                .assets(new LinkedList<>())
                .investedBalance(Money.zero("USD"))
                .uncommittedEvents(uncommittedEvents)
                .build();
    }
}
