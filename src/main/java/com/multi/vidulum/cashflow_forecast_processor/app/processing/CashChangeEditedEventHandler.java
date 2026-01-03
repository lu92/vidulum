package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import static com.multi.vidulum.cashflow_forecast_processor.app.GroupedTransactions.ReplacementFrom.from;
import static com.multi.vidulum.cashflow_forecast_processor.app.GroupedTransactions.ReplacementTo.to;
import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;

@Component
@AllArgsConstructor
public class CashChangeEditedEventHandler implements CashFlowEventHandler<CashFlowEvent.CashChangeEditedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CashChangeEditedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        CashFlowMonthlyForecast.CashChangeLocation cashChangeLocation = statement.locate(event.cashChangeId())
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Cannot find CashChange with id[%s]", event.cashChangeId())));
        statement.getForecasts().compute(cashChangeLocation.yearMonth(), (yearMonth1, cashFlowMonthlyForecast) -> {
            assert cashFlowMonthlyForecast != null;

            if (Type.INFLOW.equals(cashChangeLocation.type())) {
                GroupedTransactions groupedTransactions = cashFlowMonthlyForecast.findCashCategoryForCashChange(
                                event.cashChangeId(),
                                cashFlowMonthlyForecast.getCategorizedInFlows())
                        .map(CashCategory::getGroupedTransactions)
                        .orElseThrow();

                Transaction transaction = groupedTransactions
                        .findTransaction(event.cashChangeId());

                TransactionDetails editedTransactionDetails = new TransactionDetails(
                        event.cashChangeId(),
                        event.name(),
                        event.money(),
                        transaction.transactionDetails().getCreated(),
                        event.dueDate(),
                        transaction.transactionDetails().getEndDate()
                );

                groupedTransactions
                        .replace(
                                from(transaction.paymentStatus(), transaction.transactionDetails()),
                                to(transaction.paymentStatus(), editedTransactionDetails));

                CashSummary inflowStats = cashFlowMonthlyForecast.getCashFlowStats().getInflowStats();
                cashFlowMonthlyForecast.getCashFlowStats()
                        .setInflowStats(
                                new CashSummary(
                                        PAID.equals(transaction.paymentStatus()) ? editedTransactionDetails.getMoney() : inflowStats.actual(),
                                        EXPECTED.equals(transaction.paymentStatus()) ? editedTransactionDetails.getMoney() : inflowStats.expected(),
                                        FORECAST.equals(transaction.paymentStatus()) ? editedTransactionDetails.getMoney() : inflowStats.gapToForecast()
                                )
                        );
            } else {
                GroupedTransactions groupedTransactions = cashFlowMonthlyForecast.findCashCategoryForCashChange(
                                event.cashChangeId(),
                                cashFlowMonthlyForecast.getCategorizedOutFlows())
                        .map(CashCategory::getGroupedTransactions)
                        .orElseThrow();

                Transaction transaction = groupedTransactions
                        .findTransaction(event.cashChangeId());

                TransactionDetails editedTransactionDetails = new TransactionDetails(
                        event.cashChangeId(),
                        event.name(),
                        event.money(),
                        transaction.transactionDetails().getCreated(),
                        event.dueDate(),
                        transaction.transactionDetails().getEndDate()
                );

                groupedTransactions
                        .replace(
                                from(transaction.paymentStatus(), transaction.transactionDetails()),
                                to(transaction.paymentStatus(), editedTransactionDetails));


                CashSummary outflowStats = cashFlowMonthlyForecast.getCashFlowStats().getOutflowStats();
                cashFlowMonthlyForecast.getCashFlowStats()
                        .setOutflowStats(
                                new CashSummary(
                                        PAID.equals(transaction.paymentStatus()) ? editedTransactionDetails.getMoney() : outflowStats.actual(),
                                        EXPECTED.equals(transaction.paymentStatus()) ? editedTransactionDetails.getMoney() : outflowStats.expected(),
                                        FORECAST.equals(transaction.paymentStatus()) ? editedTransactionDetails.getMoney() : outflowStats.gapToForecast()
                                )
                        );
            }

            return cashFlowMonthlyForecast;
        });

        statement.updateStats();

        updateSyncMetadata(statement, event);
        statementRepository.save(statement);
    }
}
