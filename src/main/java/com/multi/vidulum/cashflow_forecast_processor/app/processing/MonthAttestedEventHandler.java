package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatementRepository;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowMonthlyForecast;
import com.multi.vidulum.cashflow_forecast_processor.app.TransactionDetails;
import com.multi.vidulum.common.Checksum;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.EXPECTED;

@Component
@AllArgsConstructor
public class MonthAttestedEventHandler implements CashFlowEventHandler<CashFlowEvent.MonthAttestedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.MonthAttestedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        YearMonth actualPeriod = event.period();

        CashFlowMonthlyForecast actualCashFlowMonthlyForecast = statement.getForecasts().get(actualPeriod);
        CashFlowMonthlyForecast nextCashFlowMonthlyForecast = statement.getForecasts().get(actualPeriod.plusMonths(1));
        actualCashFlowMonthlyForecast.setStatus(CashFlowMonthlyForecast.Status.ATTESTED);
        nextCashFlowMonthlyForecast.setStatus(CashFlowMonthlyForecast.Status.ACTIVE);

        //TODO: consider {@link MonthAttestedEvent.currentMoney} as end of month
        // and eventual generation of attestation when there is diff between bankAccount and calculation of app
        moveExpectedCashChangesToNextMonth(statement, actualPeriod);
        statement.addNextForecastAtTheTop();

        statement.updateStats();

        Checksum lastMessageChecksum = getChecksum(event);
        statement.setLastMessageChecksum(lastMessageChecksum);
        statementRepository.save(statement);
    }

    private static void moveExpectedCashChangesToNextMonth(CashFlowForecastStatement statement, YearMonth actualPeriod) {
        CashFlowMonthlyForecast actualCashFlowMonthlyForecast = statement.getForecasts().get(actualPeriod);

        List<CashChangeId> cashChangesWithExpectedPayment = actualCashFlowMonthlyForecast.getCategorizedInFlows()
                .get(0)
                .getGroupedTransactions().get(EXPECTED)
                .stream()
                .map(TransactionDetails::getCashChangeId).toList();
        YearMonth nextPeriod = actualPeriod.plusMonths(1);

        cashChangesWithExpectedPayment
                .forEach(cashChangeId -> statement.move(
                        cashChangeId,
                        actualPeriod,
                        nextPeriod));
    }
}
