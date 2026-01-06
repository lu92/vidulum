package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.app.processing.CashFlowForecastProcessor;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class CashFlowEventListener {

    private final CashFlowForecastProcessor cashFlowForecastProcessor;

    @KafkaListener(
            groupId = "group_id7",
            topics = "cash_flow",
            containerFactory = "cashFlowUnifiedEventContainerFactory")
    public void on(CashFlowUnifiedEvent event) {
        log.debug("CashFlowUnifiedEvent captured: [{}]", event);
        CashFlowEvent cashFlowEvent = map(event);
        cashFlowForecastProcessor.process(cashFlowEvent);
    }

    private CashFlowEvent map(CashFlowUnifiedEvent event) {
        String eventType = (String) event.getMetadata().get("event");
        switch (eventType) {
            case "CashFlowCreatedEvent" -> {
                return event.getContent().to(CashFlowEvent.CashFlowCreatedEvent.class);
            }
            case "CashFlowWithHistoryCreatedEvent" -> {
                return event.getContent().to(CashFlowEvent.CashFlowWithHistoryCreatedEvent.class);
            }
            case "HistoricalCashChangeImportedEvent" -> {
                return event.getContent().to(CashFlowEvent.HistoricalCashChangeImportedEvent.class);
            }
            case "MonthAttestedEvent" -> {
                return event.getContent().to(CashFlowEvent.MonthAttestedEvent.class);
            }
            case "ExpectedCashChangeAppendedEvent" -> {
                return event.getContent().to(CashFlowEvent.ExpectedCashChangeAppendedEvent.class);
            }
            case "PaidCashChangeAppendedEvent" -> {
                return event.getContent().to(CashFlowEvent.PaidCashChangeAppendedEvent.class);
            }
            case "CashChangeEditedEvent" -> {
                return event.getContent().to(CashFlowEvent.CashChangeEditedEvent.class);
            }
            case "CashChangeConfirmedEvent" -> {
                return event.getContent().to(CashFlowEvent.CashChangeConfirmedEvent.class);
            }
            case "CashChangeRejectedEvent" -> {
                return event.getContent().to(CashFlowEvent.CashChangeRejectedEvent.class);
            }
            case "CategoryCreatedEvent" -> {
                return event.getContent().to(CashFlowEvent.CategoryCreatedEvent.class);
            }
            case "BudgetingSetEvent" -> {
                return event.getContent().to(CashFlowEvent.BudgetingSetEvent.class);
            }
            case "BudgetingUpdatedEvent" -> {
                return event.getContent().to(CashFlowEvent.BudgetingUpdatedEvent.class);
            }
            case "BudgetingRemovedEvent" -> {
                return event.getContent().to(CashFlowEvent.BudgetingRemovedEvent.class);
            }
            default -> throw new IllegalStateException("Unexpected value: " + eventType);
        }
    }
}
