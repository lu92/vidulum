package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Stack;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;

@Data
@Builder
@AllArgsConstructor
public class CashFlowMonthlyForecast {
    private YearMonth period;
    private CashFlowStats cashFlowStats;
    private List<CashCategory> categorizedInFlows;
    private List<CashCategory> categorizedOutFlows;
    private Status status;
    private Attestation attestation;

    public void addToInflows(Transaction transaction) {
        // in future there will be more inflowCategories, now only operating on first element
        CashCategory pickedCashCategory = categorizedInFlows.get(0);

        pickedCashCategory
                .getGroupedTransactions()
                .addTransaction(transaction);

        CashSummary inflowStats = cashFlowStats.getInflowStats();
        cashFlowStats
                .setInflowStats(
                        new CashSummary(
                                PAID.equals(transaction.paymentStatus()) ? inflowStats.actual().plus(transaction.transactionDetails().getMoney()) : inflowStats.actual(),
                                EXPECTED.equals(transaction.paymentStatus()) ? inflowStats.expected().plus(transaction.transactionDetails().getMoney()) : inflowStats.expected(),
                                FORECAST.equals(transaction.paymentStatus()) ? inflowStats.gapToForecast().plus(transaction.transactionDetails().getMoney()) : inflowStats.gapToForecast()
                        )
                );

        if (transaction.isPaid()) {
            Money updatedTotalValue = pickedCashCategory
                    .getTotalPaidValue()
                    .plus(transaction.transactionDetails().getMoney());
            pickedCashCategory.setTotalPaidValue(updatedTotalValue);
        }
    }

    public void addToInflows(CategoryName categoryName, Transaction transaction) {
        // in future there will be more inflowCategories, now only operating on first element

        CashCategory cashCategory = findCategoryInflowsByCategoryName(categoryName).orElseThrow();

        cashCategory
                .getGroupedTransactions()
                .addTransaction(transaction);

        CashSummary inflowStats = cashFlowStats.getInflowStats();
        cashFlowStats
                .setInflowStats(
                        new CashSummary(
                                PAID.equals(transaction.paymentStatus()) ? inflowStats.actual().plus(transaction.transactionDetails().getMoney()) : inflowStats.actual(),
                                EXPECTED.equals(transaction.paymentStatus()) ? inflowStats.expected().plus(transaction.transactionDetails().getMoney()) : inflowStats.expected(),
                                FORECAST.equals(transaction.paymentStatus()) ? inflowStats.gapToForecast().plus(transaction.transactionDetails().getMoney()) : inflowStats.gapToForecast()
                        )
                );

        if (transaction.isPaid()) {
            Money updatedTotalValue = cashCategory
                    .getTotalPaidValue()
                    .plus(transaction.transactionDetails().getMoney());
            cashCategory.setTotalPaidValue(updatedTotalValue);
        }
    }

    public void removeFromInflows(Transaction transaction) {
        categorizedInFlows
                .get(0)
                .getGroupedTransactions()
                .removeTransaction(transaction);

        CashSummary inflowStatsToDecrease = cashFlowStats.getInflowStats();
        cashFlowStats
                .setInflowStats(
                        new CashSummary(
                                PAID.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.actual().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.actual(),
                                EXPECTED.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.expected().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.expected(),
                                FORECAST.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.gapToForecast().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.gapToForecast()
                        )
                );
    }

    public void removeFromInflows(CategoryName categoryName, Transaction transaction) {
        CashCategory cashCategory = findCategoryInflowsByCategoryName(categoryName).orElseThrow();

        cashCategory
                .getGroupedTransactions()
                .removeTransaction(transaction);

        CashSummary inflowStatsToDecrease = cashFlowStats.getInflowStats();
        cashFlowStats
                .setInflowStats(
                        new CashSummary(
                                PAID.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.actual().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.actual(),
                                EXPECTED.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.expected().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.expected(),
                                FORECAST.equals(transaction.paymentStatus()) ? inflowStatsToDecrease.gapToForecast().minus(transaction.transactionDetails().getMoney()) : inflowStatsToDecrease.gapToForecast()
                        )
                );
    }

    public void addToOutflows(Transaction transaction) {
        // in future there will be more inflowCategories, now only operating on first element
        CashCategory pickedCashCategory = categorizedOutFlows.get(0);

        pickedCashCategory
                .getGroupedTransactions()
                .addTransaction(transaction);

        CashSummary outflowStats = cashFlowStats.getOutflowStats();
        cashFlowStats
                .setOutflowStats(
                        new CashSummary(
                                PAID.equals(transaction.paymentStatus()) ? outflowStats.actual().plus(transaction.transactionDetails().getMoney()) : outflowStats.actual(),
                                EXPECTED.equals(transaction.paymentStatus()) ? outflowStats.expected().plus(transaction.transactionDetails().getMoney()) : outflowStats.expected(),
                                FORECAST.equals(transaction.paymentStatus()) ? outflowStats.gapToForecast().plus(transaction.transactionDetails().getMoney()) : outflowStats.gapToForecast()
                        )
                );

        if (transaction.isPaid()) {
            Money updatedTotalValue = pickedCashCategory
                    .getTotalPaidValue()
                    .plus(transaction.transactionDetails().getMoney());
            pickedCashCategory.setTotalPaidValue(updatedTotalValue);
        }
    }

    public void addToOutflows(CategoryName categoryName, Transaction transaction) {
        // in future there will be more inflowCategories, now only operating on first element
        CashCategory pickedCashCategory = findCategoryOutflowsByCategoryName(categoryName).orElseThrow();


        pickedCashCategory
                .getGroupedTransactions()
                .addTransaction(transaction);

        CashSummary outflowStats = cashFlowStats.getOutflowStats();
        cashFlowStats
                .setOutflowStats(
                        new CashSummary(
                                PAID.equals(transaction.paymentStatus()) ? outflowStats.actual().plus(transaction.transactionDetails().getMoney()) : outflowStats.actual(),
                                EXPECTED.equals(transaction.paymentStatus()) ? outflowStats.expected().plus(transaction.transactionDetails().getMoney()) : outflowStats.expected(),
                                FORECAST.equals(transaction.paymentStatus()) ? outflowStats.gapToForecast().plus(transaction.transactionDetails().getMoney()) : outflowStats.gapToForecast()
                        )
                );

        if (transaction.isPaid()) {
            Money updatedTotalValue = pickedCashCategory
                    .getTotalPaidValue()
                    .plus(transaction.transactionDetails().getMoney());
            pickedCashCategory.setTotalPaidValue(updatedTotalValue);
        }
    }

    public void removeFromOutflows(Transaction transaction) {
        categorizedOutFlows
                .get(0)
                .getGroupedTransactions()
                .removeTransaction(transaction);

        CashSummary outflowStatsToDecrease = cashFlowStats.getOutflowStats();
        cashFlowStats
                .setOutflowStats(
                        new CashSummary(
                                PAID.equals(transaction.paymentStatus()) ? outflowStatsToDecrease.actual().minus(transaction.transactionDetails().getMoney()) : outflowStatsToDecrease.actual(),
                                EXPECTED.equals(transaction.paymentStatus()) ? outflowStatsToDecrease.expected().minus(transaction.transactionDetails().getMoney()) : outflowStatsToDecrease.expected(),
                                FORECAST.equals(transaction.paymentStatus()) ? outflowStatsToDecrease.gapToForecast().minus(transaction.transactionDetails().getMoney()) : outflowStatsToDecrease.gapToForecast()
                        )
                );
    }

    public void removeFromOutflows(CategoryName categoryName, Transaction transaction) {
        CashCategory cashCategory = findCategoryOutflowsByCategoryName(categoryName).orElseThrow();


        cashCategory
                .getGroupedTransactions()
                .removeTransaction(transaction);

        CashSummary outflowStatsToDecrease = cashFlowStats.getOutflowStats();
        cashFlowStats
                .setOutflowStats(
                        new CashSummary(
                                PAID.equals(transaction.paymentStatus()) ? outflowStatsToDecrease.actual().minus(transaction.transactionDetails().getMoney()) : outflowStatsToDecrease.actual(),
                                EXPECTED.equals(transaction.paymentStatus()) ? outflowStatsToDecrease.expected().minus(transaction.transactionDetails().getMoney()) : outflowStatsToDecrease.expected(),
                                FORECAST.equals(transaction.paymentStatus()) ? outflowStatsToDecrease.gapToForecast().minus(transaction.transactionDetails().getMoney()) : outflowStatsToDecrease.gapToForecast()
                        )
                );
    }

    /**
     * Diff between all incomes and outcomes
     */
    public Money calcNetChange() {
        Currency inFlowCurrency = Currency.of(categorizedInFlows.get(0).getTotalPaidValue().getCurrency());
        Money totalIncomeValue = categorizedInFlows.stream()
                .map(CashCategory::getGroupedTransactions)
                .map(GroupedTransactions::values)
                .flatMap(Collection::stream)
                .flatMap(Collection::stream)
                .map(TransactionDetails::getMoney)
                .reduce(Money.zero(inFlowCurrency.getId()), Money::plus);

        Currency outFlowCurrency = Currency.of(categorizedOutFlows.get(0).getTotalPaidValue().getCurrency());
        Money totalOutcomeValue = categorizedOutFlows.stream()
                .map(CashCategory::getGroupedTransactions)
                .map(GroupedTransactions::values)
                .flatMap(Collection::stream)
                .flatMap(Collection::stream)
                .map(TransactionDetails::getMoney)
                .reduce(Money.zero(outFlowCurrency.getId()), Money::plus);

        return totalIncomeValue.minus(totalOutcomeValue);
    }

    public Optional<CashCategory> findCategoryInflowsByCategoryName(CategoryName categoryName) {
        return findCategoryByCategoryName(categoryName, categorizedInFlows);
    }

    public Optional<CashCategory> findCategoryOutflowsByCategoryName(CategoryName categoryName) {
        return findCategoryByCategoryName(categoryName, categorizedOutFlows);
    }

    private Optional<CashCategory> findCategoryByCategoryName(CategoryName categoryName, List<CashCategory> cashCategories) {
        Stack<CashCategory> stack = new Stack<>();
        cashCategories.forEach(stack::push);
        while (!stack.isEmpty()) {
            CashCategory takenCashCategory = stack.pop();
            if (takenCashCategory.getCategoryName().equals(categoryName)) {
                return Optional.of(takenCashCategory);
            }
            takenCashCategory.getSubCategories().forEach(stack::push);
        }
        return Optional.empty();
    }

    public Optional<CashCategory> findCashCategoryForCashChange(CashChangeId cashChangeId, List<CashCategory> cashCategories) {
        Stack<CashCategory> stack = new Stack<>();
        cashCategories.forEach(stack::push);
        while (!stack.isEmpty()) {
            CashCategory takenCashCategory = stack.pop();
            boolean isMatched = takenCashCategory.getGroupedTransactions().getTransactions()
                    .values().stream()
                    .flatMap(Collection::stream)
                    .anyMatch(transactionDetails -> transactionDetails.getCashChangeId().equals(cashChangeId));

            if (isMatched) {
                return Optional.of(takenCashCategory);
            }
            takenCashCategory.getSubCategories().forEach(stack::push);
        }
        return Optional.empty();
    }

    public record CashChangeLocation(CashChangeId cashChangeId, YearMonth yearMonth, Type type,
                                     Transaction transaction,
                                     CategoryName categoryName) {
    }

    public enum Status {
        ATTESTED, ACTIVE, FORECASTED
    }
}
