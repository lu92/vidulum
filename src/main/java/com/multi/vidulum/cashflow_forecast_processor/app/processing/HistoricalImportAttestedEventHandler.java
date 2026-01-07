package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Handler for HistoricalImportAttestedEvent.
 * Changes all IMPORT_PENDING months to IMPORTED status.
 * If an adjustment was created, adds it to the ACTIVE month.
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

        // If adjustment was created, add it to the ACTIVE month
        if (event.adjustmentCashChangeId() != null) {
            addAdjustmentToActiveMonth(statement, event);
        }

        statement.updateStats();
        statement.setLastMessageChecksum(getChecksum(event));
        statementRepository.save(statement);

        log.info("Historical import attested for CashFlow [{}]. Changed [{}] months from IMPORT_PENDING to IMPORTED. " +
                        "Confirmed balance: [{}], Calculated balance: [{}], Difference: [{}], AdjustmentCreated: [{}]",
                event.cashFlowId().id(), updatedCount,
                event.confirmedBalance(), event.calculatedBalance(), event.balanceDifference(),
                event.adjustmentCashChangeId() != null);
    }

    private void addAdjustmentToActiveMonth(CashFlowForecastStatement statement, CashFlowEvent.HistoricalImportAttestedEvent event) {
        // Find the ACTIVE month
        CashFlowMonthlyForecast activeMonth = statement.getForecasts().values().stream()
                .filter(f -> f.getStatus() == CashFlowMonthlyForecast.Status.ACTIVE)
                .findFirst()
                .orElse(null);

        if (activeMonth == null) {
            log.warn("No ACTIVE month found for adjustment in CashFlow [{}]", event.cashFlowId().id());
            return;
        }

        Money difference = event.balanceDifference();
        Type adjustmentType = difference.isPositive() ? Type.INFLOW : Type.OUTFLOW;
        Money adjustmentAmount = difference.abs();

        // Create the adjustment transaction
        TransactionDetails adjustmentDetails = TransactionDetails.builder()
                .cashChangeId(event.adjustmentCashChangeId())
                .name(new Name("Balance Adjustment"))
                .money(adjustmentAmount)
                .created(event.attestedAt())
                .dueDate(event.attestedAt())
                .endDate(event.attestedAt())
                .build();

        Transaction adjustmentTransaction = new Transaction(adjustmentDetails, PaymentStatus.PAID);

        // Find Uncategorized category in the appropriate flow
        java.util.List<CashCategory> categories = adjustmentType == Type.INFLOW
                ? activeMonth.getCategorizedInFlows()
                : activeMonth.getCategorizedOutFlows();

        CashCategory uncategorizedCategory = categories.stream()
                .filter(c -> "Uncategorized".equals(c.getCategoryName().name()))
                .findFirst()
                .orElse(null);

        if (uncategorizedCategory != null) {
            uncategorizedCategory.getGroupedTransactions().addTransaction(adjustmentTransaction);
            log.info("Added adjustment transaction [{}] of [{}] [{}] to ACTIVE month [{}]",
                    event.adjustmentCashChangeId().id(), adjustmentAmount, adjustmentType, activeMonth.getPeriod());
        } else {
            log.warn("Uncategorized category not found for adjustment in CashFlow [{}]", event.cashFlowId().id());
        }
    }
}
