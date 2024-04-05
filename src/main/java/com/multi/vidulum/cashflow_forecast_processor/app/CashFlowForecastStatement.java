package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.YearMonth;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.multi.vidulum.cashflow.domain.Type.INFLOW;

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

    public void addNextForecastAtTheTop() {
        CashFlowMonthlyForecast lastForecast = findLastMonthlyForecast();
        YearMonth upcomingPeriod = lastForecast.getPeriod().plusMonths(1);
        Money beginningBalance = lastForecast.getCashFlowStats().getEnd();
        List<CashCategory> categorizedInflows = new LinkedList<>();
        categorizedInflows.add(
                CashCategory.builder()
                        .categoryName(new CategoryName("Uncategorized"))
                        .category(new Category("Uncategorized"))
                        .subCategories(List.of())
                        .groupedTransactions(new GroupedTransactions())
                        .totalPaidValue(Money.zero(bankAccountNumber.denomination().getId()))
                        .build()
        );

        forecasts.put(
                upcomingPeriod,
                new CashFlowMonthlyForecast(
                        upcomingPeriod,
                        CashFlowStats.justBalance(beginningBalance),
                        categorizedInflows,
                        List.of(
                                CashCategory.builder()
                                        .categoryName(new CategoryName("Uncategorized"))
                                        .category(new Category("Uncategorized"))
                                        .subCategories(List.of())
                                        .groupedTransactions(new GroupedTransactions())
                                        .totalPaidValue(Money.zero(bankAccountNumber.denomination().getId()))
                                        .build()
                        ),
                        CashFlowMonthlyForecast.Status.FORECASTED,
                        null
                )
        );
    }

    public void updateStats() {
        String currency = bankAccountNumber.denomination().getId();
        Money outcome = forecasts.values().stream()
                .reduce(
                        Money.zero(currency),
                        (totalStart, cashFlowMonthlyForecast) -> {

                            Money netChange = cashFlowMonthlyForecast.calcNetChange();
                            CashFlowStats cashFlowStats = cashFlowMonthlyForecast.getCashFlowStats();
                            cashFlowMonthlyForecast.setCashFlowStats(
                                    new CashFlowStats(
                                            totalStart,
                                            totalStart.plus(netChange),
                                            netChange,
                                            cashFlowStats.getInflowStats(),
                                            cashFlowStats.getOutflowStats())
                            );

                            return totalStart.plus(netChange);
                        },
                        Money::plus);
    }

    public CashFlowMonthlyForecast findLastMonthlyForecast() {
        YearMonth lastPeriod = forecasts.keySet()
                .stream().max(YearMonth::compareTo)
                .orElseThrow();
        return forecasts.get(lastPeriod);
    }
}
