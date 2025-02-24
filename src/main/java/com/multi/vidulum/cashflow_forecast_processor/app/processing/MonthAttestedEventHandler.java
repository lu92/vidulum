package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Checksum;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;

import static com.multi.vidulum.cashflow_forecast_processor.app.Attestation.Type.MANUAL;
import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.EXPECTED;

@Slf4j
@Component
@AllArgsConstructor
public class MonthAttestedEventHandler implements CashFlowEventHandler<CashFlowEvent.MonthAttestedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.MonthAttestedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));
        YearMonth actualPeriod = statement.fetchCurrentPeriod();

        log.info("Attempt of attestation of month: [{}]", actualPeriod);

        if (!statement.fetchCurrentPeriod().plusMonths(1).equals(event.period())) {
            throw new IllegalArgumentException(String.format("Cannot attest not-active period: %s, active period [%s]", event.period(), statement.fetchCurrentPeriod()));
        }

        CashFlowMonthlyForecast actualCashFlowMonthlyForecast = statement.getForecasts().get(actualPeriod);

        CashFlowMonthlyForecast nextCashFlowMonthlyForecast = statement.getForecasts().get(actualPeriod.plusMonths(1));
        actualCashFlowMonthlyForecast.setStatus(CashFlowMonthlyForecast.Status.ATTESTED);
        actualCashFlowMonthlyForecast.setAttestation(
                new Attestation(
                        event.currentMoney(),
                        MANUAL,
                        event.dateTime()
                )
        );
        nextCashFlowMonthlyForecast.setStatus(CashFlowMonthlyForecast.Status.ACTIVE);

        //TODO: consider {@link MonthAttestedEvent.currentMoney} as end of month
        // and eventual generation of attestation when there is diff between bankAccount and calculation of app
        moveExpectedCashChangesToNextMonth(statement, actualPeriod);
        statement.addNextForecastAtTheTop();

        statement.updateStats();

        Checksum lastMessageChecksum = getChecksum(event);
        statement.setLastMessageChecksum(lastMessageChecksum);
        statementRepository.save(statement);
        log.info("Month attested: [{}]", actualPeriod);
    }

    private static void moveExpectedCashChangesToNextMonth(CashFlowForecastStatement statement, YearMonth actualPeriod) {
        CashFlowMonthlyForecast actualCashFlowMonthlyForecast = statement.getForecasts().get(actualPeriod);
        YearMonth nextPeriod = actualPeriod.plusMonths(1);

        Stream.concat(
                        actualCashFlowMonthlyForecast.getCategorizedInFlows().stream(),
                        actualCashFlowMonthlyForecast.getCategorizedOutFlows().stream())
                .forEach(cashCategory -> {
                    List<CashChangeId> cashChangesWithExpectedPayment = cashCategory
                            .getGroupedTransactions().get(EXPECTED)
                            .stream()
                            .map(TransactionDetails::getCashChangeId).toList();

                    cashChangesWithExpectedPayment
                            .forEach(cashChangeId -> statement.move(
                                    cashChangeId,
                                    actualPeriod,
                                    nextPeriod));
                });
    }
}
