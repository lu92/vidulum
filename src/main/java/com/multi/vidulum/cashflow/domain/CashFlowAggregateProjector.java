package com.multi.vidulum.cashflow.domain;

import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
public class CashFlowAggregateProjector {

    public CashFlow process(Collection<CashFlowEvent> events) {
        CashFlow cashFlow = new CashFlow();
        events.forEach(processingEvent -> {
            switch (processingEvent) {
                case CashFlowEvent.CashFlowCreatedEvent event -> cashFlow.apply(event);
                case CashFlowEvent.MonthAttestedEvent event -> cashFlow.apply(event);
                case CashFlowEvent.CashChangeAppendedEvent event -> cashFlow.apply(event);
                case CashFlowEvent.CashChangeConfirmedEvent event -> cashFlow.apply(event);
                case CashFlowEvent.CashChangeEditedEvent event -> cashFlow.apply(event);
                case CashFlowEvent.CashChangeRejectedEvent event -> cashFlow.apply(event);
            }
        });
        return cashFlow;
    }
}
