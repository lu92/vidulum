package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;

/**
 * Handles CashChangesBatchUpdatedEvent by updating transactions in the forecast statement.
 */
@Slf4j
@Component
@AllArgsConstructor
public class CashChangesBatchUpdatedEventHandler implements CashFlowEventHandler<CashFlowEvent.CashChangesBatchUpdatedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CashChangesBatchUpdatedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        int updatedCount = 0;

        // Check if category change is requested
        CategoryName newCategoryName = event.changes().containsKey("categoryName")
                ? (CategoryName) event.changes().get("categoryName")
                : null;

        for (CashChangeId cashChangeId : event.updatedIds()) {
            CashFlowMonthlyForecast.CashChangeLocation location = statement.locate(cashChangeId)
                    .orElse(null);

            if (location == null) {
                log.debug("CashChange [{}] not found in forecast for batch update, skipping", cashChangeId);
                continue;
            }

            CashFlowMonthlyForecast forecast = statement.getForecasts().get(location.yearMonth());
            Transaction oldTransaction = location.transaction();
            TransactionDetails oldDetails = oldTransaction.transactionDetails();
            PaymentStatus paymentStatus = oldTransaction.paymentStatus();

            // Build new transaction details
            Name newName = event.changes().containsKey("name")
                    ? (Name) event.changes().get("name")
                    : oldDetails.getName();
            Money newMoney = event.changes().containsKey("amount")
                    ? (Money) event.changes().get("amount")
                    : oldDetails.getMoney();

            TransactionDetails newDetails = new TransactionDetails(
                    cashChangeId,
                    newName,
                    newMoney,
                    oldDetails.getCreated(),
                    oldDetails.getDueDate(),
                    oldDetails.getEndDate()
            );
            Transaction newTransaction = new Transaction(newDetails, paymentStatus);

            // Handle category change if needed
            if (newCategoryName != null && !newCategoryName.equals(location.categoryName())) {
                // Move transaction to new category
                if (Type.INFLOW.equals(location.type())) {
                    forecast.removeFromInflows(location.categoryName(), oldTransaction);
                    forecast.addToInflows(newCategoryName, newTransaction);
                } else {
                    forecast.removeFromOutflows(location.categoryName(), oldTransaction);
                    forecast.addToOutflows(newCategoryName, newTransaction);
                }
            } else {
                // Update in place
                CashCategory category = findCategory(forecast, location.type(), location.categoryName());
                if (category != null) {
                    category.getGroupedTransactions().replace(
                            new GroupedTransactions.ReplacementFrom(paymentStatus, oldDetails),
                            new GroupedTransactions.ReplacementTo(paymentStatus, newDetails)
                    );

                    // Update statistics with the difference
                    Money diff = newMoney.minus(oldDetails.getMoney());
                    if (!diff.getAmount().equals(java.math.BigDecimal.ZERO)) {
                        updateStatsWithDiff(forecast, location.type(), paymentStatus, diff);
                    }
                }
            }
            updatedCount++;
        }

        statement.updateStats();

        // Update sync metadata
        updateSyncMetadata(statement, event);

        statementRepository.save(statement);

        log.debug("Batch updated [{}] transactions in forecast for cashFlowId [{}], sourceRuleId [{}]. Changes: {}",
                updatedCount, event.cashFlowId().id(), event.sourceRuleId(), event.changes().keySet());
    }

    private CashCategory findCategory(CashFlowMonthlyForecast forecast, Type type, CategoryName categoryName) {
        if (Type.INFLOW.equals(type)) {
            return forecast.findCategoryInflowsByCategoryName(categoryName).orElse(null);
        } else {
            return forecast.findCategoryOutflowsByCategoryName(categoryName).orElse(null);
        }
    }

    private void updateStatsWithDiff(CashFlowMonthlyForecast forecast, Type type, PaymentStatus paymentStatus, Money diff) {
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
