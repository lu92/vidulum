package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Checksum;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;

@Component
@AllArgsConstructor
public class MonthAttestedEventHandler implements CashFlowEventHandler<CashFlowEvent.MonthAttestedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.MonthAttestedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        YearMonth actualPeriod = event.period();
        YearMonth nextPeriod = actualPeriod.plusMonths(1);

        CashFlowMonthlyForecast actualCashFlowMonthlyForecast = statement.getForecasts().get(actualPeriod);
        CashFlowMonthlyForecast nextCashFlowMonthlyForecast = statement.getForecasts().get(nextPeriod);
        actualCashFlowMonthlyForecast.setStatus(CashFlowMonthlyForecast.Status.ATTESTED);
        nextCashFlowMonthlyForecast.setStatus(CashFlowMonthlyForecast.Status.ACTIVE);


        List<CashChangeId> cashChangesWithExpectedPayment = actualCashFlowMonthlyForecast.getCategorizedInFlows()
                .get(0)
                .getGroupedTransactions().get(EXPECTED)
                .stream()
                .map(TransactionDetails::getCashChangeId).toList();

        cashChangesWithExpectedPayment.stream().forEach(cashChangeId -> {
            statement.move(cashChangeId, actualPeriod, nextPeriod);

        });

//        CashSummary inflowStats = cashFlowMonthlyForecast.getCashFlowStats().getInflowStats();
//
//        cashFlowMonthlyForecast.getCashFlowStats()
//                .setInflowStats(
//                        new CashSummary(
//                                PAID.equals(transaction.paymentStatus()) ? inflowStats.actual().minus(transaction.transactionDetails().getMoney()) : inflowStats.actual(),
//                                EXPECTED.equals(transaction.paymentStatus()) ? inflowStats.expected().minus(transaction.transactionDetails().getMoney()) : inflowStats.expected(),
//                                FORECAST.equals(transaction.paymentStatus()) ? inflowStats.actual().minus(transaction.transactionDetails().getMoney()) : inflowStats.gapToForecast()
//                        )
//                );



        // TODO add new Month Forecast

        Checksum lastMessageChecksum = getChecksum(event);
        statement.setLastMessageChecksum(lastMessageChecksum);
        statementRepository.save(statement);
    }
}
