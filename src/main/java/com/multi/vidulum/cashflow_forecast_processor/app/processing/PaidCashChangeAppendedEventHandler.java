package com.multi.vidulum.cashflow_forecast_processor.app.processing;

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
            // VID-161 Phase 1b: route self-transfers to dedicated bucket (no inflow/outflow stats update).
            // addToSelfTransfer*flows auto-creates the bucket category on first use.
            if (event.selfTransfer()) {
                TransactionDetails details = new TransactionDetails(
                        event.cashChangeId(),
                        event.name(),
                        event.money(),
                        event.created(),
                        event.dueDate(),
                        event.paidDate()
                );
                Transaction txn = new Transaction(details, PAID);
                if (Type.INFLOW.equals(event.type())) {
                    cashFlowMonthlyForecast.addToSelfTransferInflows(event.categoryName(), txn);
                    cashCategory = cashFlowMonthlyForecast.findCategoryInSelfTransferInflowsByName(event.categoryName()).orElseThrow();
                } else {
                    cashFlowMonthlyForecast.addToSelfTransferOutflows(event.categoryName(), txn);
                    cashCategory = cashFlowMonthlyForecast.findCategoryInSelfTransferOutflowsByName(event.categoryName()).orElseThrow();
                }
                cashCategory.setTotalPaidValue(cashCategory.getTotalPaidValue().plus(event.money()));
                return cashFlowMonthlyForecast;  // already added via add*Flows
            } else if (Type.INFLOW.equals(event.type())) {
                cashCategory = cashFlowMonthlyForecast.findCategoryInflowsByCategoryName(event.categoryName())
                        .orElseThrow(() -> new IllegalStateException(String.format("Cannot find cash-category with name %s in INFLOWS", event.categoryName())));
                CashFlowStats currentCashFlowStats = cashFlowMonthlyForecast.getCashFlowStats();
                CashSummary inflowCashSummary = currentCashFlowStats.getInflowStats();
                // update stats - directly to actual since it's already paid
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
                        .orElseThrow(() -> new IllegalStateException(String.format("Cannot find cash-category with name %s in OUTFLOWS", event.categoryName())));
                CashSummary outflowCashSummary = cashFlowMonthlyForecast.getCashFlowStats().getOutflowStats();
                // update stats - directly to actual since it's already paid
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

            // Add transaction directly to PAID group
            cashCategory.getGroupedTransactions().get(PAID)
                    .add(
                            new TransactionDetails(
                                    event.cashChangeId(),
                                    event.name(),
                                    event.money(),
                                    event.created(),
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
