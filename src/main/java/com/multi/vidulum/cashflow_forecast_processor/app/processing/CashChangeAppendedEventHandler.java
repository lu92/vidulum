package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.EXPECTED;

@Component
@AllArgsConstructor
public class CashChangeAppendedEventHandler implements CashFlowEventHandler<CashFlowEvent.CashChangeAppendedEvent> {

    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CashChangeAppendedEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(event.cashFlowId()));

        YearMonth yearMonth = YearMonth.from(event.created());
        statement.getForecasts().compute(yearMonth, (yearMonth1, cashFlowMonthlyForecast) -> {

            // for now there is only one 'unknown' category for both inflow/outflow
            CashCategory unknownCashCategory;
            if (Type.INFLOW.equals(event.type())) {
                unknownCashCategory = cashFlowMonthlyForecast.getCategorizedInFlows().get(0);
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

//                Money netChange = cashFlowMonthlyForecast.calcNetChange();
//                cashFlowMonthlyForecast.setCashFlowStats(
//                        new CashFlowStats(
//                                currentCashFlowStats.getStart(),
//                                netChange.plus(currentCashFlowStats.getEnd()),
//                                netChange,
//                                currentCashFlowStats.getInflowStats(),
//                                currentCashFlowStats.getOutflowStats()
//                        )
//                );

            } else {
                unknownCashCategory = cashFlowMonthlyForecast.getCategorizedOutFlows().get(0);
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
            unknownCashCategory.getGroupedTransactions().get(EXPECTED)
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

//        String currency = statement.getBankAccountNumber().denomination().getId();
//        Money outcome = statement.getForecasts().values().stream()
//                .reduce(
//                        Money.zero(currency),
//                        (totalStart, cashFlowMonthlyForecast) -> {
//
//                            Money netChange = cashFlowMonthlyForecast.calcNetChange();
//
//                            CashFlowStats cashFlowStats = cashFlowMonthlyForecast.getCashFlowStats();
//                            cashFlowMonthlyForecast.setCashFlowStats(
//                                    new CashFlowStats(
//                                            totalStart,
//                                            totalStart.plus(netChange),
//                                            netChange,
//                                            cashFlowStats.getInflowStats(),
//                                            cashFlowStats.getOutflowStats())
//                            );
//
//                            Money actualStart = totalStart.plus(netChange);
//
//                            System.out.println("actual start" + actualStart);
//                            return actualStart;
//                        },
//                        Money::plus);

        Checksum lastMessageChecksum = getChecksum(event);
        statement.setLastMessageChecksum(lastMessageChecksum);
        statementRepository.save(statement);
    }
}
