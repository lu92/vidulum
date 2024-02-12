package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Checksum;
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
                CashSummary inflowCashSummary = cashFlowMonthlyForecast.getCashFlowStats().getInflowStats();
                // update stats
                cashFlowMonthlyForecast.getCashFlowStats().setInflowStats(
                        new CashSummary(
                                inflowCashSummary.actual(),
                                inflowCashSummary.expected().plus(event.money()),
                                inflowCashSummary.gapToForecast()
                        )
                );
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
            unknownCashCategory.getTransactions().get(EXPECTED)
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

        Checksum lastMessageChecksum = getChecksum(event);
        statement.setLastMessageChecksum(lastMessageChecksum);
        statementRepository.save(statement);
    }
}
