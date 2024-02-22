package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Checksum;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;
import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.FORECAST;

@Component
@AllArgsConstructor
public class CashChangeRejectedEventHandler implements CashFlowEventHandler<CashFlowEvent.CashChangeRejectedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CashChangeRejectedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        CashFlowMonthlyForecast.CashChangeLocation cashChangeLocation = statement.locate(event.cashChangeId())
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Cannot find CashChange with id[%s]", event.cashChangeId())));

        statement.getForecasts().compute(cashChangeLocation.yearMonth(), (yearMonth1, cashFlowMonthlyForecast) -> {

            if (Type.INFLOW.equals(cashChangeLocation.type())) {
                Transaction transaction = cashFlowMonthlyForecast.getCategorizedInFlows()
                        .get(0)
                        .getGroupedTransactions()
                        .findTransaction(event.cashChangeId());

                cashFlowMonthlyForecast.getCategorizedInFlows()
                        .get(0)
                        .getGroupedTransactions().get(transaction.paymentStatus())
                        .remove(transaction.transactionDetails());

                CashSummary inflowStats = cashFlowMonthlyForecast.getCashFlowStats().getInflowStats();

                cashFlowMonthlyForecast.getCashFlowStats()
                        .setInflowStats(
                                new CashSummary(
                                        PAID.equals(transaction.paymentStatus()) ? inflowStats.actual().minus(transaction.transactionDetails().getMoney()) : inflowStats.actual(),
                                        EXPECTED.equals(transaction.paymentStatus()) ? inflowStats.expected().minus(transaction.transactionDetails().getMoney()) : inflowStats.expected(),
                                        FORECAST.equals(transaction.paymentStatus()) ? inflowStats.gapToForecast().minus(transaction.transactionDetails().getMoney()) : inflowStats.gapToForecast()
                                )
                        );

            } else {
                Transaction transaction = cashFlowMonthlyForecast.getCategorizedOutFlows()
                        .get(0)
                        .getGroupedTransactions()
                        .findTransaction(event.cashChangeId());

                cashFlowMonthlyForecast.getCategorizedOutFlows()
                        .get(0)
                        .getGroupedTransactions().get(transaction.paymentStatus())
                        .remove(transaction.transactionDetails());

                CashSummary outflowStats = cashFlowMonthlyForecast.getCashFlowStats().getOutflowStats();

                cashFlowMonthlyForecast.getCashFlowStats()
                        .setOutflowStats(
                                new CashSummary(
                                        PAID.equals(transaction.paymentStatus()) ? outflowStats.actual().minus(transaction.transactionDetails().getMoney()) : outflowStats.actual(),
                                        EXPECTED.equals(transaction.paymentStatus()) ? outflowStats.expected().minus(transaction.transactionDetails().getMoney()) : outflowStats.expected(),
                                        FORECAST.equals(transaction.paymentStatus()) ? outflowStats.actual().minus(transaction.transactionDetails().getMoney()) : outflowStats.gapToForecast()
                                )
                        );
            }

            return cashFlowMonthlyForecast;
        });

        Checksum lastMessageChecksum = getChecksum(event);
        statement.setLastMessageChecksum(lastMessageChecksum);
        statementRepository.save(statement);
    }
}
