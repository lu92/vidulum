package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Checksum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashFlowForecastStatement {
    private CashFlowId cashFlowId;
    private Map<YearMonth, CashFlowMonthlyForecast> forecasts; // next 12 months
    private Checksum lastMessageChecksum;

    public Optional<CashFlowMonthlyForecast.CashChangeLocation> locate(CashChangeId cashChangeId) {
        return forecasts.values().stream()
                .map(cashFlowMonthlyForecast -> {
                    Optional<CashFlowMonthlyForecast.CashChangeLocation> inflowCashChangeLocation = cashFlowMonthlyForecast.getCategorizedInFlows().stream()
                            .map(CashCategory::getGroupedTransactions)
                            .map(GroupedTransactions::getTransactions)
                            .flatMap(paymentStatusListMap -> paymentStatusListMap.entrySet().stream())
                            .map(entries -> {
                                PaymentStatus paymentStatus = entries.getKey();
                                List<TransactionDetails> transactionDetails = entries.getValue();

                                return transactionDetails.stream()
                                        .filter(transactionDetail -> cashChangeId.equals(transactionDetail.getCashChangeId()))
                                        .findFirst()
                                        .map(transactionDetail -> new CashFlowMonthlyForecast.CashChangeLocation(
                                                transactionDetail.getCashChangeId(),
                                                cashFlowMonthlyForecast.getPeriod(),
                                                INFLOW,
                                                new Transaction(transactionDetail, paymentStatus)));

                            })
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .findFirst();

                    Optional<CashFlowMonthlyForecast.CashChangeLocation> outflowCashChangeLocation = cashFlowMonthlyForecast.getCategorizedOutFlows().stream()
                            .map(CashCategory::getGroupedTransactions)
                            .map(GroupedTransactions::getTransactions)
                            .flatMap(paymentStatusListMap -> paymentStatusListMap.entrySet().stream())
                            .map(entries -> {
                                PaymentStatus paymentStatus = entries.getKey();
                                List<TransactionDetails> transactionDetails = entries.getValue();

                                return transactionDetails.stream()
                                        .filter(transactionDetail -> cashChangeId.equals(transactionDetail.getCashChangeId()))
                                        .findFirst()
                                        .map(transactionDetail -> new CashFlowMonthlyForecast.CashChangeLocation(
                                                transactionDetail.getCashChangeId(),
                                                cashFlowMonthlyForecast.getPeriod(),
                                                Type.OUTFLOW,
                                                new Transaction(transactionDetail, paymentStatus)));

                            })
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .findFirst();

                    return inflowCashChangeLocation.or(() -> outflowCashChangeLocation);
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    public void move(CashChangeId cashChangeId, YearMonth fromPeriod, YearMonth toPeriod) {
        CashFlowMonthlyForecast.CashChangeLocation location = locate(cashChangeId)
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Cannot find CashChange with id[%s]", cashChangeId)));

        CashFlowMonthlyForecast cashFlowMonthlyForecastReadyToDecrease = forecasts.get(fromPeriod);
        CashFlowMonthlyForecast cashFlowMonthlyForecastToIncrease = forecasts.get(toPeriod);
        Transaction transaction = location.transaction();


        if (INFLOW.equals(location.type())) {
            cashFlowMonthlyForecastReadyToDecrease
                    .getCategorizedInFlows()
                    .get(0)
                    .getGroupedTransactions()
                    .removeTransaction(transaction);


            CashFlowStats cashFlowStatsReadyToDecrease = cashFlowMonthlyForecastReadyToDecrease.getCashFlowStats();
            CashSummary inflowStatsToDecrease = cashFlowStatsReadyToDecrease.getInflowStats();

            cashFlowStatsReadyToDecrease
                    .setInflowStats(
                            new CashSummary(
                                    PAID.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.actual().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.actual(),
                                    EXPECTED.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.expected().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.expected(),
                                    FORECAST.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.gapToForecast().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.gapToForecast()
                            )
                    );

            cashFlowMonthlyForecastToIncrease
                    .getCategorizedInFlows()
                    .get(0)
                    .getGroupedTransactions()
                    .addTransaction(transaction);

            CashFlowStats cashFlowStatsReadyToIncrease = cashFlowMonthlyForecastToIncrease.getCashFlowStats();
            CashSummary inflowStats = cashFlowStatsReadyToIncrease.getInflowStats();

            cashFlowStatsReadyToIncrease
                    .setInflowStats(
                            new CashSummary(
                                    PAID.equals(transaction.paymentStatus()) ? inflowStats.actual().plus(transaction.transactionDetails().getMoney()) : inflowStats.actual(),
                                    EXPECTED.equals(transaction.paymentStatus()) ? inflowStats.expected().plus(transaction.transactionDetails().getMoney()) : inflowStats.expected(),
                                    FORECAST.equals(transaction.paymentStatus()) ? inflowStats.gapToForecast().plus(transaction.transactionDetails().getMoney()) : inflowStats.gapToForecast()
                            )
                    );
        }

    }
}
