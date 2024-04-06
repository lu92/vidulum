package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.YearMonth;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;

@Data
@Slf4j
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashFlowForecastStatement {
    private CashFlowId cashFlowId;
    private Map<YearMonth, CashFlowMonthlyForecast> forecasts;// next 12 months
    private BankAccountNumber bankAccountNumber;
    private Checksum lastMessageChecksum;

    public Optional<CashFlowMonthlyForecast.CashChangeLocation> locate(CashChangeId cashChangeId) {
        return forecasts.values().stream()
                .map(cashFlowMonthlyForecast -> {
                    Optional<CashFlowMonthlyForecast.CashChangeLocation> inflowCashChangeLocation =
                            cashFlowMonthlyForecast.getCategorizedInFlows().stream()
                                    .map(cashCategory -> {
                                        CategoryName categoryName = cashCategory.getCategoryName();
                                        return cashCategory.getGroupedTransactions().getTransactions().entrySet()
                                                .stream()
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
                                                                    new Transaction(transactionDetail, paymentStatus),
                                                                    categoryName));
                                                })
                                                .filter(Optional::isPresent)
                                                .map(Optional::get)
                                                .findFirst();
                                    })
                                    .filter(Optional::isPresent)
                                    .findFirst()
                                    .orElse(Optional.empty());

                    Optional<CashFlowMonthlyForecast.CashChangeLocation> outflowCashChangeLocation =
                            cashFlowMonthlyForecast.getCategorizedOutFlows().stream()
                                    .map(cashCategory -> {
                                        CategoryName categoryName = cashCategory.getCategoryName();
                                        return cashCategory.getGroupedTransactions().getTransactions().entrySet()
                                                .stream()
                                                .map(entries -> {
                                                    PaymentStatus paymentStatus = entries.getKey();
                                                    List<TransactionDetails> transactionDetails = entries.getValue();
                                                    return transactionDetails.stream()
                                                            .filter(transactionDetail -> cashChangeId.equals(transactionDetail.getCashChangeId()))
                                                            .findFirst()
                                                            .map(transactionDetail -> new CashFlowMonthlyForecast.CashChangeLocation(
                                                                    transactionDetail.getCashChangeId(),
                                                                    cashFlowMonthlyForecast.getPeriod(),
                                                                    OUTFLOW,
                                                                    new Transaction(transactionDetail, paymentStatus),
                                                                    categoryName));
                                                })
                                                .filter(Optional::isPresent)
                                                .map(Optional::get)
                                                .findFirst();
                                    })
                                    .filter(Optional::isPresent)
                                    .findFirst()
                                    .orElse(Optional.empty());

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
            cashFlowMonthlyForecastReadyToDecrease.removeFromInflows(location.categoryName(), transaction);
            cashFlowMonthlyForecastToIncrease.addToInflows(location.categoryName(), transaction);
        } else {
            cashFlowMonthlyForecastReadyToDecrease.removeFromOutflows(location.categoryName(), transaction);
            cashFlowMonthlyForecastToIncrease.addToOutflows(location.categoryName(), transaction);
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
                            CashFlowStats updatedCashFlowStats = new CashFlowStats(
                                    totalStart,
                                    totalStart.plus(netChange),
                                    netChange,
                                    cashFlowStats.getInflowStats(),
                                    cashFlowStats.getOutflowStats());

                            cashFlowMonthlyForecast.setCashFlowStats(updatedCashFlowStats);
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
