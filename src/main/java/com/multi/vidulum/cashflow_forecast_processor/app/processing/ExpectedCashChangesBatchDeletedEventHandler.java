package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Handles ExpectedCashChangesBatchDeletedEvent by removing transactions from the forecast statement.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ExpectedCashChangesBatchDeletedEventHandler implements CashFlowEventHandler<CashFlowEvent.ExpectedCashChangesBatchDeletedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.ExpectedCashChangesBatchDeletedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        int deletedCount = 0;

        for (CashChangeId cashChangeId : event.deletedIds()) {
            CashFlowMonthlyForecast.CashChangeLocation location = statement.locate(cashChangeId)
                    .orElse(null);

            if (location == null) {
                log.debug("CashChange [{}] not found in forecast for batch deletion, skipping", cashChangeId);
                continue;
            }

            CashFlowMonthlyForecast forecast = statement.getForecasts().get(location.yearMonth());
            Transaction transaction = location.transaction();

            // Remove from the appropriate category based on type
            if (Type.INFLOW.equals(location.type())) {
                forecast.removeFromInflows(location.categoryName(), transaction);
            } else {
                forecast.removeFromOutflows(location.categoryName(), transaction);
            }
            deletedCount++;
        }

        statement.updateStats();

        // Update sync metadata
        updateSyncMetadata(statement, event);

        statementRepository.save(statement);

        log.debug("Batch deleted [{}] transactions from forecast for cashFlowId [{}], sourceRuleId [{}]",
                deletedCount, event.cashFlowId().id(), event.sourceRuleId());
    }
}
