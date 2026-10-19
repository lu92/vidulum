package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatementRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Projects a whole batch of appended cash changes with <b>one</b> read and <b>one</b> write of the
 * statement, however many items it carries.
 *
 * <p>That is the point of the batch event existing. The single-append handler does the same work
 * per event; for a daily recurring rule - 365 occurrences in the forecast window - this is one
 * statement round trip instead of 365, and one {@code updateStats()} instead of 365.
 */
@Component
@AllArgsConstructor
public class ExpectedCashChangesBatchAppendedEventHandler
        implements CashFlowEventHandler<CashFlowEvent.ExpectedCashChangesBatchAppendedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;
    private final ExpectedCashChangeProjector projector;

    @Override
    public void handle(CashFlowEvent.ExpectedCashChangesBatchAppendedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowNotFoundException(event.cashFlowId()));

        event.appended().forEach(appended -> projector.project(statement, appended, event.created()));

        statement.updateStats();
        updateSyncMetadata(statement, event);
        statementRepository.save(statement);
    }
}
