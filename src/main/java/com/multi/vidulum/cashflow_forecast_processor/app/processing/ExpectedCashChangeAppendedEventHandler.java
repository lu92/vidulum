package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.EXPECTED;

@Component
@AllArgsConstructor
public class ExpectedCashChangeAppendedEventHandler implements CashFlowEventHandler<CashFlowEvent.ExpectedCashChangeAppendedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.ExpectedCashChangeAppendedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        // Use dueDate to determine which month the transaction belongs to
        // (not created date, which is when the event was emitted)
        YearMonth yearMonth = YearMonth.from(event.dueDate());
        statement.getForecasts().compute(yearMonth, (yearMonth1, cashFlowMonthlyForecast) -> {

            // for now there is only one 'Uncategorized' category for both inflow/outflow
            CashCategory uncategorizedCashCategory;
            if (Type.INFLOW.equals(event.type())) {
                uncategorizedCashCategory = cashFlowMonthlyForecast.findCategoryInflowsByCategoryName(event.categoryName())
                        .orElseThrow(() -> new IllegalStateException(String.format("Cannot find cash-category with name %s in INFLOWS", event.categoryName())));
                CashFlowStats currentCashFlowStats = cashFlowMonthlyForecast.getCashFlowStats();
                CashSummary inflowCashSummary = currentCashFlowStats.getInflowStats();
                // update stats
                currentCashFlowStats.setInflowStats(
                        new CashSummary(
                                inflowCashSummary.actual(),
                                inflowCashSummary.expected().plus(event.money()),
                                inflowCashSummary.gapToForecast()
                        )
                );
            } else {
                uncategorizedCashCategory = cashFlowMonthlyForecast.findCategoryOutflowsByCategoryName(event.categoryName())
                        .orElseThrow(() -> new IllegalStateException(String.format("Cannot find cash-category with name %s in OUTFLOWS", event.categoryName())));
                CashSummary outflowCashSummary = cashFlowMonthlyForecast.getCashFlowStats().getOutflowStats();
                // update stats
                cashFlowMonthlyForecast.getCashFlowStats().setOutflowStats(
                        new CashSummary(
                                outflowCashSummary.actual(),
                                outflowCashSummary.expected().plus(event.money()),
                                outflowCashSummary.gapToForecast()
                        )
                );
            }
            uncategorizedCashCategory.getGroupedTransactions().get(EXPECTED)
                    .add(
                            new TransactionDetails(
                                    event.cashChangeId(),
                                    event.name(),
                                    event.money(),
                                    event.created(),
                                    event.dueDate(),
                                    null
                            )
                    );
            return cashFlowMonthlyForecast;
        });

        statement.updateStats();

        updateSyncMetadata(statement, event);
        statementRepository.save(statement);
    }
}
