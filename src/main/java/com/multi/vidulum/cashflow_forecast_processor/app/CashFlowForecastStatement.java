package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Money;
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
    private Map<YearMonth, CashFlowMonthlyForecast> forecasts;// next 12 months
    private BankAccountNumber bankAccountNumber;
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
            cashFlowMonthlyForecastReadyToDecrease.removeFromInflows(transaction);
            cashFlowMonthlyForecastToIncrease.addToInflows(transaction);
        } else {
            cashFlowMonthlyForecastReadyToDecrease.removeFromOutflows(transaction);
            cashFlowMonthlyForecastToIncrease.addToOutflows(transaction);
        }
    }

    public YearMonth fetchCurrentPeriod() {
        return forecasts.values()
                .stream()
                .filter(cashFlowMonthlyForecast -> cashFlowMonthlyForecast.getStatus().equals(CashFlowMonthlyForecast.Status.ACTIVE))
                .findFirst()
                .map(CashFlowMonthlyForecast::getPeriod)
                .orElseThrow(() -> new IllegalStateException(""));
    }

    public void addEmptyForecast(YearMonth period, Money beginningBalance) {
        forecasts.put(
                period,
                new CashFlowMonthlyForecast(
                        period,
                        CashFlowStats.justBalance(beginningBalance),
                        List.of(
                                CashCategory.builder()
                                        .category(new Category("unknown"))
                                        .subCategories(List.of())
                                        .groupedTransactions(new GroupedTransactions())
                                        .totalValue(Money.zero(bankAccountNumber.denomination().getId()))
                                        .build()
                        ),
                        List.of(
                                CashCategory.builder()
                                        .category(new Category("unknown"))
                                        .subCategories(List.of())
                                        .groupedTransactions(new GroupedTransactions())
                                        .totalValue(Money.zero(bankAccountNumber.denomination().getId()))
                                        .build()
                        ),
                        CashFlowMonthlyForecast.Status.FORECASTED
                )
        );
    }
}
