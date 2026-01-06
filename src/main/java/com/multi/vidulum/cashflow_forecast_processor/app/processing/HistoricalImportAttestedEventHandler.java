package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatementRepository;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowMonthlyForecast;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Handler for HistoricalImportAttestedEvent.
 * Changes all IMPORT_PENDING months to IMPORTED status.
 */
@Slf4j
@Component
@AllArgsConstructor
public class HistoricalImportAttestedEventHandler implements CashFlowEventHandler<CashFlowEvent.HistoricalImportAttestedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.HistoricalImportAttestedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new IllegalStateException(
                        "CashFlowForecastStatement not found for CashFlow: " + event.cashFlowId().id()));

        int updatedCount = 0;

        // Change all IMPORT_PENDING months to IMPORTED
        for (CashFlowMonthlyForecast forecast : statement.getForecasts().values()) {
            if (forecast.getStatus() == CashFlowMonthlyForecast.Status.IMPORT_PENDING) {
                forecast.setStatus(CashFlowMonthlyForecast.Status.IMPORTED);
                updatedCount++;
            }
        }

        statement.setLastMessageChecksum(getChecksum(event));
        statementRepository.save(statement);

        log.info("Historical import attested for CashFlow [{}]. Changed [{}] months from IMPORT_PENDING to IMPORTED. " +
                        "Confirmed balance: [{}], Calculated balance: [{}], Difference: [{}]",
                event.cashFlowId().id(), updatedCount,
                event.confirmedBalance(), event.calculatedBalance(), event.balanceDifference());
    }
}
