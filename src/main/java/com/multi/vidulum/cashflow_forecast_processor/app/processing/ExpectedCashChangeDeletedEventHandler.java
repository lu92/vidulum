package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Handles ExpectedCashChangeDeletedEvent by removing the transaction from the forecast statement.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ExpectedCashChangeDeletedEventHandler implements CashFlowEventHandler<CashFlowEvent.ExpectedCashChangeDeletedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.ExpectedCashChangeDeletedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        // Locate the transaction in the forecast
        CashFlowMonthlyForecast.CashChangeLocation location = statement.locate(event.cashChangeId())
                .orElse(null);

        if (location == null) {
            log.warn("CashChange [{}] not found in forecast for deletion, skipping", event.cashChangeId());
            return;
        }

        CashFlowMonthlyForecast forecast = statement.getForecasts().get(location.yearMonth());
        Transaction transaction = location.transaction();

        // Remove from the appropriate category based on type
        if (Type.INFLOW.equals(location.type())) {
            forecast.removeFromInflows(location.categoryName(), transaction);
        } else {
            forecast.removeFromOutflows(location.categoryName(), transaction);
        }

        statement.updateStats();

        // Update sync metadata
        updateSyncMetadata(statement, event);

        statementRepository.save(statement);

        log.debug("Deleted transaction [{}] from forecast for cashFlowId [{}], period [{}]",
                event.cashChangeId().id(), event.cashFlowId().id(), location.yearMonth());
    }
}
