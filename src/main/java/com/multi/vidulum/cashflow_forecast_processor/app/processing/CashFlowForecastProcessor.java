package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastMongoRepository;
import com.multi.vidulum.common.JsonContent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedList;

@Slf4j
@Component
@AllArgsConstructor
public class CashFlowForecastProcessor {

    private final CashFlowForecastMongoRepository repository;
    private final CashFlowCreatedEventHandler cashFlowCreatedEventHandler;
    private final CashFlowWithHistoryCreatedEventHandler cashFlowWithHistoryCreatedEventHandler;
    private final HistoricalCashChangeImportedEventHandler historicalCashChangeImportedEventHandler;
    private final MonthAttestedEventHandler monthAttestedEventHandler;
    private final ExpectedCashChangeAppendedEventHandler expectedCashChangeAppendedEventHandler;
    private final PaidCashChangeAppendedEventHandler paidCashChangeAppendedEventHandler;
    private final CashChangeConfirmedEventHandler cashChangeConfirmedEventHandler;
    private final CashChangeEditedEventHandler cashChangeEditedEventHandler;
    private final CashChangeRejectedEventHandler cashChangeRejectedEventHandler;
    private final CategoryCreatedEventHandler categoryCreatedEventHandler;
    private final BudgetingSetEventHandler budgetingSetEventHandler;
    private final BudgetingUpdatedEventHandler budgetingUpdatedEventHandler;
    private final BudgetingRemovedEventHandler budgetingRemovedEventHandler;

    public void process(CashFlowEvent cashFlowEvent) {
        oldProcessing(cashFlowEvent);
        processEvent(cashFlowEvent);
    }

    private void processEvent(CashFlowEvent cashFlowEvent) {
        switch (cashFlowEvent) {
            case CashFlowEvent.CashFlowCreatedEvent event -> cashFlowCreatedEventHandler.handle(event);
            case CashFlowEvent.CashFlowWithHistoryCreatedEvent event -> cashFlowWithHistoryCreatedEventHandler.handle(event);
            case CashFlowEvent.HistoricalCashChangeImportedEvent event -> historicalCashChangeImportedEventHandler.handle(event);
            case CashFlowEvent.MonthAttestedEvent event -> monthAttestedEventHandler.handle(event);
            case CashFlowEvent.ExpectedCashChangeAppendedEvent event -> expectedCashChangeAppendedEventHandler.handle(event);
            case CashFlowEvent.PaidCashChangeAppendedEvent event -> paidCashChangeAppendedEventHandler.handle(event);
            case CashFlowEvent.CashChangeConfirmedEvent event -> cashChangeConfirmedEventHandler.handle(event);
            case CashFlowEvent.CashChangeRejectedEvent event -> cashChangeRejectedEventHandler.handle(event);
            case CashFlowEvent.CashChangeEditedEvent event -> cashChangeEditedEventHandler.handle(event);
            case CashFlowEvent.CategoryCreatedEvent event -> categoryCreatedEventHandler.handle(event);
            case CashFlowEvent.BudgetingSetEvent event -> budgetingSetEventHandler.handle(event);
            case CashFlowEvent.BudgetingUpdatedEvent event -> budgetingUpdatedEventHandler.handle(event);
            case CashFlowEvent.BudgetingRemovedEvent event -> budgetingRemovedEventHandler.handle(event);
        }
    }

    private void oldProcessing(CashFlowEvent cashFlowEvent) {
        CashFlowForecastEntity.Processing processing = new CashFlowForecastEntity.Processing(
                cashFlowEvent.getClass().getSimpleName(),
                JsonContent.asPrettyJson(cashFlowEvent).content());

        CashFlowForecastEntity cashFlowForecastEntity = repository.findByCashFlowId(cashFlowEvent.cashFlowId().id())
                .map(entity -> {
                    entity.getEvents().add(processing);
                    return entity;
                })
                .orElseGet(() -> {
                    LinkedList<CashFlowForecastEntity.Processing> events = new LinkedList<>();
                    events.add(processing);
                    return CashFlowForecastEntity.builder()
                            .cashFlowId(cashFlowEvent.cashFlowId().id())
                            .events(events)
                            .build();
                });
        repository.save(cashFlowForecastEntity);
    }

}
