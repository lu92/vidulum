package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.app.processing.CashFlowForecastProcessor;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns a message on the {@code cash_flow} topic back into a domain event.
 *
 * <p><b>This switch is on a String, so the compiler cannot tell you when it is incomplete.</b>
 * {@code CashFlowEvent} is sealed, which is why the switches in {@code CashFlowAggregateProjector}
 * and {@code CashFlowForecastProcessor} refuse to compile when an event is added - but here the name
 * arrives as metadata, and a missing case means {@code default} throws at runtime: the event is
 * retried nine times and dropped while the write that produced it answered 2xx. A new event type
 * needs a line here, and the test that notices is the one asserting the projection changed.
 */
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
            case "HistoricalImportAttestedEvent" -> {
                return event.getContent().to(CashFlowEvent.HistoricalImportAttestedEvent.class);
            }
            case "ImportRolledBackEvent" -> {
                return event.getContent().to(CashFlowEvent.ImportRolledBackEvent.class);
            }
            case "MonthAttestedEvent" -> {
                return event.getContent().to(CashFlowEvent.MonthAttestedEvent.class);
            }
            case "MonthRolledOverEvent" -> {
                return event.getContent().to(CashFlowEvent.MonthRolledOverEvent.class);
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
            case "CategoryArchivedEvent" -> {
                return event.getContent().to(CashFlowEvent.CategoryArchivedEvent.class);
            }
            case "CategoryUnarchivedEvent" -> {
                return event.getContent().to(CashFlowEvent.CategoryUnarchivedEvent.class);
            }
            case "CategoryMovedEvent" -> {
                return event.getContent().to(CashFlowEvent.CategoryMovedEvent.class);
            }
            case "ExpectedCashChangeDeletedEvent" -> {
                return event.getContent().to(CashFlowEvent.ExpectedCashChangeDeletedEvent.class);
            }
            case "ExpectedCashChangesBatchAppendedEvent" -> {
                return event.getContent().to(CashFlowEvent.ExpectedCashChangesBatchAppendedEvent.class);
            }
            case "ExpectedCashChangesBatchDeletedEvent" -> {
                return event.getContent().to(CashFlowEvent.ExpectedCashChangesBatchDeletedEvent.class);
            }
            case "CashChangesBatchUpdatedEvent" -> {
                return event.getContent().to(CashFlowEvent.CashChangesBatchUpdatedEvent.class);
            }
            default -> throw new IllegalStateException("Unexpected value: " + eventType);
        }
    }
}
