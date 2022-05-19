package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.*;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.util.LinkedList;
import java.util.List;

public class PortfolioFactory {

    public Portfolio empty(PortfolioId portfolioId, String name, UserId userId, Broker broker, Currency allowedDepositCurrency) {
        List<DomainEvent> uncommittedEvents = new LinkedList<>();
        uncommittedEvents.add(
                new PortfolioEvents.PortfolioOpenedEvent(
                        portfolioId,
                        name,
                        broker
                )
        );
        return Portfolio.builder()
                .portfolioId(portfolioId)
                .userId(userId)
                .name(name)
                .broker(broker)
                .assets(new LinkedList<>())
                .allowedDepositCurrency(allowedDepositCurrency)
                .investedBalance(Money.zero(allowedDepositCurrency.getId()))
                .status(PortfolioStatus.OPEN)
                .uncommittedEvents(uncommittedEvents)
                .build();
    }
}
