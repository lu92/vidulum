package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.PAID;

/**
 * Handler for HistoricalCashChangeImportedEvent.
 * Adds historical transactions to IMPORT_PENDING months.
 * Historical transactions are added directly as PAID since they represent past confirmed transactions.
 */
@Slf4j
@Component
@AllArgsConstructor
public class HistoricalCashChangeImportedEventHandler implements CashFlowEventHandler<CashFlowEvent.HistoricalCashChangeImportedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    /**
     * WORKAROUND: Retry configuration for race condition between CategoryCreatedEvent and HistoricalCashChangeImportedEvent.
     *
     * When importing historical transactions, the CategoryCreatedEvent and HistoricalCashChangeImportedEvent
     * are sent to Kafka in order, but may be processed by different consumers or with slight delays.
     * This causes a race condition where HistoricalCashChangeImportedEvent may be processed before
     * the category exists in the read model (forecast_statement).
     *
     * This retry mechanism waits for the category to appear in the read model before processing.
     * A proper fix would require ensuring event ordering at the architecture level (e.g., saga pattern,
     * single consumer, or including category data directly in the transaction event).
     */
    private static final int MAX_RETRIES = 10;
    private static final long INITIAL_BACKOFF_MS = 100;

    @Override
    public void handle(CashFlowEvent.HistoricalCashChangeImportedEvent event) {
        // WORKAROUND: Retry the entire operation with backoff to handle race condition
        // where CategoryCreatedEvent may not yet be processed when HistoricalCashChangeImportedEvent arrives
        handleWithRetry(event);
    }

    /**
     * WORKAROUND: Wraps the entire handle logic with retry mechanism.
     * This handles race condition where category may not exist yet in the read model.
     */
    private void handleWithRetry(CashFlowEvent.HistoricalCashChangeImportedEvent event) {
        long backoffMs = INITIAL_BACKOFF_MS;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                doHandle(event);
                return; // Success - exit retry loop
            } catch (IllegalStateException e) {
                // Check if this is a "category not found" error - these are retriable
                if (e.getMessage() != null && e.getMessage().contains("Cannot find cash-category")) {
                    lastException = e;
                    if (attempt < MAX_RETRIES) {
                        log.warn("WORKAROUND: Category [{}] not found for cashflow [{}], retry {}/{} after {}ms. Error: {}",
                                event.categoryName(), event.cashFlowId(), attempt, MAX_RETRIES, backoffMs, e.getMessage());
                        try {
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while waiting for category to appear", ie);
                        }
                        backoffMs = Math.min(backoffMs * 2, 2000); // Exponential backoff, max 2 seconds
                    }
                } else {
                    // Not a retriable error - rethrow immediately
                    throw e;
                }
            } catch (CashFlowDoesNotExistsException e) {
                // CashFlow/Statement not found - also retriable
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    log.warn("WORKAROUND: Statement not found for cashflow [{}], retry {}/{} after {}ms",
                            event.cashFlowId(), attempt, MAX_RETRIES, backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for statement to appear", ie);
                    }
                    backoffMs = Math.min(backoffMs * 2, 2000);
                }
            }
        }

        // All retries exhausted - throw the last exception
        log.error("WORKAROUND: All {} retries exhausted for category [{}] in cashflow [{}]",
                MAX_RETRIES, event.categoryName(), event.cashFlowId());
        if (lastException instanceof RuntimeException) {
            throw (RuntimeException) lastException;
        }
        throw new RuntimeException("Failed after " + MAX_RETRIES + " retries", lastException);
    }

    /**
     * Original handle logic extracted to separate method for retry wrapper.
     */
    private void doHandle(CashFlowEvent.HistoricalCashChangeImportedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        // Use paidDate to determine the month for historical data
        YearMonth yearMonth = YearMonth.from(event.paidDate());
        statement.getForecasts().compute(yearMonth, (period, cashFlowMonthlyForecast) -> {
            if (cashFlowMonthlyForecast == null) {
                throw new IllegalStateException(String.format(
                        "Cannot import to period [%s] - month does not exist in forecast", period));
            }

            CashCategory cashCategory;
            if (Type.INFLOW.equals(event.type())) {
                cashCategory = cashFlowMonthlyForecast.findCategoryInflowsByCategoryName(event.categoryName())
                        .orElseThrow(() -> new IllegalStateException(String.format(
                                "Cannot find cash-category with name %s in INFLOWS", event.categoryName())));
                CashFlowStats currentCashFlowStats = cashFlowMonthlyForecast.getCashFlowStats();
                CashSummary inflowCashSummary = currentCashFlowStats.getInflowStats();
                // update stats - directly to actual since this is historical (already paid) data
                currentCashFlowStats.setInflowStats(
                        new CashSummary(
                                inflowCashSummary.actual().plus(event.money()),
                                inflowCashSummary.expected(),
                                inflowCashSummary.gapToForecast()
                        )
                );
                // update total paid value for category
                cashCategory.setTotalPaidValue(cashCategory.getTotalPaidValue().plus(event.money()));
            } else {
                cashCategory = cashFlowMonthlyForecast.findCategoryOutflowsByCategoryName(event.categoryName())
                        .orElseThrow(() -> new IllegalStateException(String.format(
                                "Cannot find cash-category with name %s in OUTFLOWS", event.categoryName())));
                CashSummary outflowCashSummary = cashFlowMonthlyForecast.getCashFlowStats().getOutflowStats();
                // update stats - directly to actual since this is historical (already paid) data
                cashFlowMonthlyForecast.getCashFlowStats().setOutflowStats(
                        new CashSummary(
                                outflowCashSummary.actual().plus(event.money()),
                                outflowCashSummary.expected(),
                                outflowCashSummary.gapToForecast()
                        )
                );
                // update total paid value for category
                cashCategory.setTotalPaidValue(cashCategory.getTotalPaidValue().plus(event.money()));
            }

            // Add transaction directly to PAID group (historical data is already confirmed)
            cashCategory.getGroupedTransactions().get(PAID)
                    .add(
                            new TransactionDetails(
                                    event.cashChangeId(),
                                    event.name(),
                                    event.money(),
                                    event.importedAt(),
                                    event.dueDate(),
                                    event.paidDate()
                            )
                    );
            return cashFlowMonthlyForecast;
        });

        statement.updateStats();

        updateSyncMetadata(statement, event);
        statementRepository.save(statement);
    }

}
