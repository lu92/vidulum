package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashChangeDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Checksum;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import static com.multi.vidulum.cashflow_forecast_processor.app.GroupedTransactions.ReplacementFrom.from;
import static com.multi.vidulum.cashflow_forecast_processor.app.GroupedTransactions.ReplacementTo.to;
import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.EXPECTED;
import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.PAID;

@Component
@AllArgsConstructor
public class CashChangeConfirmedEventHandler implements CashFlowEventHandler<CashFlowEvent.CashChangeConfirmedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CashChangeConfirmedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        CashFlowMonthlyForecast.CashChangeLocation cashChangeLocation = statement.locate(event.cashChangeId())
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Cannot find CashChange with id[%s]", event.cashChangeId())));

        statement.getForecasts().compute(cashChangeLocation.yearMonth(), (yearMonth1, cashFlowMonthlyForecast) -> {

            TransactionDetails transactionDetails =
                    cashFlowMonthlyForecast.getCategorizedInFlows().get(0)
                            .getGroupedTransactions().fetchTransaction(event.cashChangeId())
                            .or(() ->
                                    cashFlowMonthlyForecast.getCategorizedOutFlows().get(0)
                                            .getGroupedTransactions().fetchTransaction(event.cashChangeId()))
                            .map(Transaction::transactionDetails)
                            .orElseThrow(() -> new CashChangeDoesNotExistsException(event.cashChangeId()));


            TransactionDetails newTransaction = new TransactionDetails(
                    transactionDetails.getCashChangeId(),
                    transactionDetails.getName(),
                    transactionDetails.getMoney(),
                    transactionDetails.getCreated(),
                    transactionDetails.getDueDate(),
                    event.endDate()
            );


            if (Type.INFLOW.equals(cashChangeLocation.type())) {
                CashSummary inflowStats = cashFlowMonthlyForecast.getCashFlowStats().getInflowStats();
                cashFlowMonthlyForecast.getCashFlowStats().setInflowStats(
                        new CashSummary(
                                inflowStats.actual().plus(transactionDetails.getMoney()),
                                inflowStats.expected().minus(transactionDetails.getMoney()),
                                inflowStats.gapToForecast()
                        )
                );

                cashFlowMonthlyForecast.getCategorizedInFlows()
                        .get(0)
                        .getGroupedTransactions()
                        .replace(from(EXPECTED, transactionDetails), to(PAID, newTransaction));

            } else {
                CashSummary outflowStats = cashFlowMonthlyForecast.getCashFlowStats().getOutflowStats();
                cashFlowMonthlyForecast.getCashFlowStats().setOutflowStats(
                        new CashSummary(
                                outflowStats.actual().plus(transactionDetails.getMoney()),
                                outflowStats.expected().minus(transactionDetails.getMoney()),
                                outflowStats.gapToForecast()
                        )
                );

                cashFlowMonthlyForecast.getCategorizedOutFlows()
                        .get(0)
                        .getGroupedTransactions()
                        .replace(from(EXPECTED, transactionDetails), to(PAID, newTransaction));

            }
            return cashFlowMonthlyForecast;
        });

        Checksum lastMessageChecksum = getChecksum(event);
        statement.setLastMessageChecksum(lastMessageChecksum);
        statementRepository.save(statement);
    }
}
