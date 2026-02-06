package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;

/**
 * Handles CashChangeEditedEvent in the forecast processor.
 * <p>
 * Supports three types of changes:
 * <ul>
 *   <li>Simple edit (name, description, money) - updates transaction in place</li>
 *   <li>Category change - moves transaction to new category within same month</li>
 *   <li>Month change (dueDate changes month) - moves transaction to different month</li>
 * </ul>
 * <p>
 * Statistics are properly updated by calculating the difference between old and new values.
 * When moving between months, stats are properly decremented from old month and incremented in new month.
 */
@Slf4j
@Component
@AllArgsConstructor
public class CashChangeEditedEventHandler implements CashFlowEventHandler<CashFlowEvent.CashChangeEditedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CashChangeEditedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        CashFlowMonthlyForecast.CashChangeLocation location = statement.locate(event.cashChangeId())
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Cannot find CashChange with id[%s]", event.cashChangeId())));

        YearMonth oldMonth = location.yearMonth();
        YearMonth newMonth = YearMonth.from(event.dueDate());
        CategoryName oldCategory = location.categoryName();
        CategoryName newCategory = event.categoryName();
        Type type = location.type();
        Transaction oldTransaction = location.transaction();
        PaymentStatus paymentStatus = oldTransaction.paymentStatus();

        TransactionDetails newTransactionDetails = new TransactionDetails(
                event.cashChangeId(),
                event.name(),
                event.money(),
                oldTransaction.transactionDetails().getCreated(),
                event.dueDate(),
                oldTransaction.transactionDetails().getEndDate()
        );
        Transaction newTransaction = new Transaction(newTransactionDetails, paymentStatus);

        boolean categoryChanged = !oldCategory.equals(newCategory);
        boolean monthChanged = !oldMonth.equals(newMonth);

        if (monthChanged || categoryChanged) {
            // Move transaction: either month changed, category changed, or both
            handleMoveTransaction(statement, type, oldMonth, newMonth, oldCategory, newCategory, oldTransaction, newTransaction);
        } else {
            // Simple case: just update transaction in place with proper stats diff calculation
            handleSimpleEdit(statement, type, oldMonth, oldCategory, oldTransaction, newTransaction);
        }

        statement.updateStats();

        updateSyncMetadata(statement, event);
        statementRepository.save(statement);

        log.debug("CashChange [{}] edited: categoryChanged={}, monthChanged={} (from {} to {})",
                event.cashChangeId(), categoryChanged, monthChanged, oldMonth, newMonth);
    }

    /**
     * Handles simple edit where only name, description, or money changes.
     * Updates statistics by calculating the difference between old and new money.
     */
    private void handleSimpleEdit(CashFlowForecastStatement statement, Type type,
                                   YearMonth month, CategoryName category,
                                   Transaction oldTransaction, Transaction newTransaction) {
        CashFlowMonthlyForecast forecast = statement.getForecasts().get(month);
        PaymentStatus paymentStatus = oldTransaction.paymentStatus();
        Money oldMoney = oldTransaction.transactionDetails().getMoney();
        Money newMoney = newTransaction.transactionDetails().getMoney();
        Money diff = newMoney.minus(oldMoney);

        // Find and update the transaction in the category
        CashCategory cashCategory = findCategory(forecast, type, category);
        cashCategory.getGroupedTransactions().replace(
                new GroupedTransactions.ReplacementFrom(paymentStatus, oldTransaction.transactionDetails()),
                new GroupedTransactions.ReplacementTo(paymentStatus, newTransaction.transactionDetails())
        );

        // Update statistics with the difference (not overwrite!)
        updateStatsWithDiff(forecast, type, paymentStatus, diff);
    }

    /**
     * Handles transaction move when category or month changes.
     * Removes from old location and adds to new location.
     */
    private void handleMoveTransaction(CashFlowForecastStatement statement, Type type,
                                        YearMonth oldMonth, YearMonth newMonth,
                                        CategoryName oldCategory, CategoryName newCategory,
                                        Transaction oldTransaction, Transaction newTransaction) {
        CashFlowMonthlyForecast oldForecast = statement.getForecasts().get(oldMonth);
        CashFlowMonthlyForecast newForecast = statement.getForecasts().get(newMonth);

        // Remove from old location
        if (Type.INFLOW.equals(type)) {
            oldForecast.removeFromInflows(oldCategory, oldTransaction);
            newForecast.addToInflows(newCategory, newTransaction);
        } else {
            oldForecast.removeFromOutflows(oldCategory, oldTransaction);
            newForecast.addToOutflows(newCategory, newTransaction);
        }
    }

    /**
     * Finds a category in the forecast by type and name.
     */
    private CashCategory findCategory(CashFlowMonthlyForecast forecast, Type type, CategoryName categoryName) {
        if (Type.INFLOW.equals(type)) {
            return forecast.findCategoryInflowsByCategoryName(categoryName)
                    .orElseThrow(() -> new IllegalStateException(
                            String.format("Cannot find inflow category [%s]", categoryName)));
        } else {
            return forecast.findCategoryOutflowsByCategoryName(categoryName)
                    .orElseThrow(() -> new IllegalStateException(
                            String.format("Cannot find outflow category [%s]", categoryName)));
        }
    }

    /**
     * Updates forecast statistics by adding the difference (not overwriting).
     */
    private void updateStatsWithDiff(CashFlowMonthlyForecast forecast, Type type,
                                      PaymentStatus paymentStatus, Money diff) {
        if (Type.INFLOW.equals(type)) {
            CashSummary stats = forecast.getCashFlowStats().getInflowStats();
            forecast.getCashFlowStats().setInflowStats(
                    new CashSummary(
                            PAID.equals(paymentStatus) ? stats.actual().plus(diff) : stats.actual(),
                            EXPECTED.equals(paymentStatus) ? stats.expected().plus(diff) : stats.expected(),
                            FORECAST.equals(paymentStatus) ? stats.gapToForecast().plus(diff) : stats.gapToForecast()
                    )
            );
        } else {
            CashSummary stats = forecast.getCashFlowStats().getOutflowStats();
            forecast.getCashFlowStats().setOutflowStats(
                    new CashSummary(
                            PAID.equals(paymentStatus) ? stats.actual().plus(diff) : stats.actual(),
                            EXPECTED.equals(paymentStatus) ? stats.expected().plus(diff) : stats.expected(),
                            FORECAST.equals(paymentStatus) ? stats.gapToForecast().plus(diff) : stats.gapToForecast()
                    )
            );
        }
    }
}
