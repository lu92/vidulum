package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;

import static com.multi.vidulum.cashflow_forecast_processor.app.Attestation.Type.AUTO;
import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.EXPECTED;

/**
 * Handles MonthRolledOverEvent - the automatic or manual month rollover.
 * <p>
 * This handler:
 * <ul>
 *   <li>Changes the ACTIVE month status to ROLLED_OVER</li>
 *   <li>Changes the next FORECASTED month status to ACTIVE</li>
 *   <li>Moves expected (unpaid) cash changes to the next month</li>
 *   <li>Adds a new FORECASTED month at the end of the forecast horizon</li>
 *   <li>Updates statistics</li>
 * </ul>
 * <p>
 * This is the preferred way to close months (replaces the deprecated MonthAttestedEventHandler).
 * ROLLED_OVER months allow gap filling - importing missed transactions from bank statements.
 */
@Slf4j
@Component
@AllArgsConstructor
public class MonthRolledOverEventHandler implements CashFlowEventHandler<CashFlowEvent.MonthRolledOverEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.MonthRolledOverEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        YearMonth actualPeriod = statement.fetchCurrentPeriod();

        log.info("Processing month rollover for CashFlow [{}]: period [{}] -> [{}]",
                event.cashFlowId().id(), event.rolledOverPeriod(), event.newActivePeriod());

        // Validate that we're rolling over the current active period
        if (!actualPeriod.equals(event.rolledOverPeriod())) {
            throw new IllegalArgumentException(
                    String.format("Cannot rollover period %s, current active period is [%s]",
                            event.rolledOverPeriod(), actualPeriod));
        }

        // Validate that new active period is exactly one month after current
        if (!actualPeriod.plusMonths(1).equals(event.newActivePeriod())) {
            throw new IllegalArgumentException(
                    String.format("New active period [%s] must be exactly one month after [%s]",
                            event.newActivePeriod(), actualPeriod));
        }

        CashFlowMonthlyForecast actualCashFlowMonthlyForecast = statement.getForecasts().get(actualPeriod);
        CashFlowMonthlyForecast nextCashFlowMonthlyForecast = statement.getForecasts().get(event.newActivePeriod());

        // Change status: ACTIVE -> ROLLED_OVER
        actualCashFlowMonthlyForecast.setStatus(CashFlowMonthlyForecast.Status.ROLLED_OVER);
        actualCashFlowMonthlyForecast.setAttestation(
                new Attestation(
                        event.closingBalance(),
                        AUTO,
                        event.rolledOverAt()
                )
        );

        // Change status: FORECASTED -> ACTIVE
        nextCashFlowMonthlyForecast.setStatus(CashFlowMonthlyForecast.Status.ACTIVE);

        // Move expected (unpaid) cash changes to the next month
        moveExpectedCashChangesToNextMonth(statement, actualPeriod);

        // Add new FORECASTED month at the end of forecast horizon
        statement.addNextForecastAtTheTop();

        // Recalculate statistics
        statement.updateStats();

        // Update sync metadata
        updateSyncMetadata(statement, event);

        statementRepository.save(statement);
        log.info("Month rolled over successfully: [{}] -> [{}]", event.rolledOverPeriod(), event.newActivePeriod());
    }

    /**
     * Moves all expected (unpaid) cash changes from the rolled over month to the next month.
     * This ensures pending transactions are not lost when a month is closed.
     */
    private static void moveExpectedCashChangesToNextMonth(CashFlowForecastStatement statement, YearMonth actualPeriod) {
        CashFlowMonthlyForecast actualCashFlowMonthlyForecast = statement.getForecasts().get(actualPeriod);
        YearMonth nextPeriod = actualPeriod.plusMonths(1);

        Stream.concat(
                        actualCashFlowMonthlyForecast.getCategorizedInFlows().stream(),
                        actualCashFlowMonthlyForecast.getCategorizedOutFlows().stream())
                .forEach(cashCategory -> {
                    List<CashChangeId> cashChangesWithExpectedPayment = cashCategory
                            .getGroupedTransactions().get(EXPECTED)
                            .stream()
                            .map(TransactionDetails::getCashChangeId).toList();

                    cashChangesWithExpectedPayment
                            .forEach(cashChangeId -> statement.move(
                                    cashChangeId,
                                    actualPeriod,
                                    nextPeriod));
                });
    }
}
