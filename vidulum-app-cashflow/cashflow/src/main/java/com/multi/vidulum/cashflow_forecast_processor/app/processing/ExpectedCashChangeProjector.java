package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.domain.CashFlowEvent.ExpectedCashChangesBatchAppendedEvent.AppendedExpectedCashChange;
import com.multi.vidulum.cashflow_forecast_processor.app.CashCategory;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowStats;
import com.multi.vidulum.cashflow_forecast_processor.app.CashSummary;
import com.multi.vidulum.cashflow_forecast_processor.app.Transaction;
import com.multi.vidulum.cashflow_forecast_processor.app.TransactionDetails;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZonedDateTime;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.EXPECTED;

/**
 * Puts one expected cash change into the month it is due, and adjusts that month's expected totals.
 *
 * <p>Shared by {@link ExpectedCashChangeAppendedEventHandler} and
 * {@link ExpectedCashChangesBatchAppendedEventHandler}, and that is the whole reason it exists: the
 * arithmetic below is sixty lines of category lookup and summary updates, and two copies of it would
 * be two things to keep in step. The handlers differ only in how many items they pass and that they
 * read and save the statement once either way.
 */
@Component
class ExpectedCashChangeProjector {

    /**
     * @param statement the statement to mutate; the caller reads it, calls this per item, then
     *                  updates stats and saves - once, however many items there are
     * @param appended  the cash change to project
     * @param created   when the appending happened; on a batch this is shared by every item
     */
    void project(CashFlowForecastStatement statement, AppendedExpectedCashChange appended, ZonedDateTime created) {
        // Use dueDate to determine which month the transaction belongs to
        // (not created date, which is when the event was emitted)
        YearMonth yearMonth = YearMonth.from(appended.dueDate());

        statement.getForecasts().compute(yearMonth, (yearMonth1, cashFlowMonthlyForecast) -> {
            CashCategory uncategorizedCashCategory;
            if (appended.selfTransfer()) {
                TransactionDetails details = new TransactionDetails(
                        appended.cashChangeId(),
                        appended.name(),
                        appended.money(),
                        created,
                        appended.dueDate(),
                        null,
                        true
                );
                Transaction txn = new Transaction(details, EXPECTED);
                if (Type.INFLOW.equals(appended.type())) {
                    cashFlowMonthlyForecast.addToInflowsWithoutStats(appended.categoryName(), txn);
                    cashFlowMonthlyForecast.markCategoryAsSelfTransfer(appended.categoryName(), Type.INFLOW);
                } else {
                    cashFlowMonthlyForecast.addToOutflowsWithoutStats(appended.categoryName(), txn);
                    cashFlowMonthlyForecast.markCategoryAsSelfTransfer(appended.categoryName(), Type.OUTFLOW);
                }
                return cashFlowMonthlyForecast;
            } else if (Type.INFLOW.equals(appended.type())) {
                uncategorizedCashCategory = cashFlowMonthlyForecast.findCategoryInflowsByCategoryName(appended.categoryName())
                        .orElseThrow(() -> new IllegalStateException(String.format("Cannot find cash-category with name %s in INFLOWS", appended.categoryName())));
            } else {
                uncategorizedCashCategory = cashFlowMonthlyForecast.findCategoryOutflowsByCategoryName(appended.categoryName())
                        .orElseThrow(() -> new IllegalStateException(String.format("Cannot find cash-category with name %s in OUTFLOWS", appended.categoryName())));
            }

            // addTransaction is idempotent — returns false on Kafka redelivery.
            boolean added = uncategorizedCashCategory.getGroupedTransactions().addTransaction(new Transaction(
                    new TransactionDetails(
                            appended.cashChangeId(),
                            appended.name(),
                            appended.money(),
                            created,
                            appended.dueDate(),
                            null,
                            false
                    ), EXPECTED));

            if (added) {
                if (Type.INFLOW.equals(appended.type())) {
                    CashFlowStats currentCashFlowStats = cashFlowMonthlyForecast.getCashFlowStats();
                    CashSummary inflowCashSummary = currentCashFlowStats.getInflowStats();
                    currentCashFlowStats.setInflowStats(
                            new CashSummary(
                                    inflowCashSummary.actual(),
                                    inflowCashSummary.expected().plus(appended.money()),
                                    inflowCashSummary.gapToForecast()
                            )
                    );
                } else {
                    CashSummary outflowCashSummary = cashFlowMonthlyForecast.getCashFlowStats().getOutflowStats();
                    cashFlowMonthlyForecast.getCashFlowStats().setOutflowStats(
                            new CashSummary(
                                    outflowCashSummary.actual(),
                                    outflowCashSummary.expected().plus(appended.money()),
                                    outflowCashSummary.gapToForecast()
                            )
                    );
                }
            }
            return cashFlowMonthlyForecast;
        });
    }
}
