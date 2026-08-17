package com.multi.vidulum.cashflow_forecast_processor.app.processing;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.PAID;

@Component
@AllArgsConstructor
public class PaidCashChangeAppendedEventHandler implements CashFlowEventHandler<CashFlowEvent.PaidCashChangeAppendedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.PaidCashChangeAppendedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        // Use paidDate to determine the month (not created) - paid transactions belong to the month they were paid
        YearMonth yearMonth = YearMonth.from(event.paidDate());
        statement.getForecasts().compute(yearMonth, (yearMonth1, cashFlowMonthlyForecast) -> {

            CashCategory cashCategory;
            if (event.selfTransfer()) {
                TransactionDetails details = new TransactionDetails(
                        event.cashChangeId(),
                        event.name(),
                        event.money(),
                        event.created(),
                        event.dueDate(),
                        event.paidDate(),
                        true
                );
                Transaction txn = new Transaction(details, PAID);
                boolean added;
                if (Type.INFLOW.equals(event.type())) {
                    added = cashFlowMonthlyForecast.addToInflowsWithoutStats(event.categoryName(), txn);
                    cashFlowMonthlyForecast.markCategoryAsSelfTransfer(event.categoryName(), Type.INFLOW);
                } else {
                    added = cashFlowMonthlyForecast.addToOutflowsWithoutStats(event.categoryName(), txn);
                    cashFlowMonthlyForecast.markCategoryAsSelfTransfer(event.categoryName(), Type.OUTFLOW);
                }
                if (added) {
                    cashFlowMonthlyForecast.updateTotalPaidValue();
                }
                return cashFlowMonthlyForecast;
            } else if (Type.INFLOW.equals(event.type())) {
                cashCategory = cashFlowMonthlyForecast.findCategoryInflowsByCategoryName(event.categoryName())
                        .orElseThrow(() -> new IllegalStateException(String.format("Cannot find cash-category with name %s in INFLOWS", event.categoryName())));
            } else {
                cashCategory = cashFlowMonthlyForecast.findCategoryOutflowsByCategoryName(event.categoryName())
                        .orElseThrow(() -> new IllegalStateException(String.format("Cannot find cash-category with name %s in OUTFLOWS", event.categoryName())));
            }

            // Add transaction to PAID group.
            // addTransaction is idempotent — returns false on Kafka redelivery.
            boolean added = cashCategory.getGroupedTransactions().addTransaction(new Transaction(
                    new TransactionDetails(
                            event.cashChangeId(),
                            event.name(),
                            event.money(),
                            event.created(),
                            event.dueDate(),
                            event.paidDate(),
                            false
                    ), PAID));

            if (added) {
                if (Type.INFLOW.equals(event.type())) {
                    CashFlowStats currentCashFlowStats = cashFlowMonthlyForecast.getCashFlowStats();
                    CashSummary inflowCashSummary = currentCashFlowStats.getInflowStats();
                    currentCashFlowStats.setInflowStats(
                            new CashSummary(
                                    inflowCashSummary.actual().plus(event.money()),
                                    inflowCashSummary.expected(),
                                    inflowCashSummary.gapToForecast()
                            )
                    );
                } else {
                    CashSummary outflowCashSummary = cashFlowMonthlyForecast.getCashFlowStats().getOutflowStats();
                    cashFlowMonthlyForecast.getCashFlowStats().setOutflowStats(
                            new CashSummary(
                                    outflowCashSummary.actual().plus(event.money()),
                                    outflowCashSummary.expected(),
                                    outflowCashSummary.gapToForecast()
                            )
                    );
                }
            }

            cashFlowMonthlyForecast.updateTotalPaidValue();
            return cashFlowMonthlyForecast;
        });

        statement.updateStats();

        updateSyncMetadata(statement, event);
        statementRepository.save(statement);
    }
}
