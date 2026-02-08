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
import java.util.*;
import java.util.function.Consumer;

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
        Money totalIncomeValue = flattenCategories(categorizedInFlows).stream()
                .map(CashCategory::getGroupedTransactions)
                .map(GroupedTransactions::values)
                .flatMap(Collection::stream)
                .flatMap(Collection::stream)
                .map(TransactionDetails::getMoney)
                .reduce(Money.zero(inFlowCurrency.getId()), Money::plus);

        Currency outFlowCurrency = Currency.of(categorizedOutFlows.get(0).getTotalPaidValue().getCurrency());
        Money totalOutcomeValue = flattenCategories(categorizedOutFlows).stream()
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

    private List<CashCategory> flattenCategories(List<CashCategory> cashCategories) {
        Stack<CashCategory> stack = new Stack<>();
        List<CashCategory> outcome = new LinkedList<>();
        cashCategories.forEach(stack::push);
        while (!stack.isEmpty()) {
            CashCategory takenCashCategory = stack.pop();
            outcome.add(takenCashCategory);
            takenCashCategory.getSubCategories().forEach(stack::push);
        }
        return outcome;
    }

    public void updateTotalPaidValue() {
        Consumer<CashCategory> updateTotalPaid = cashCategory -> {
            Money totalPaidValue = flattenCategories(List.of(cashCategory)).stream()
                    .map(CashCategory::getGroupedTransactions)
                    .map(x -> x.get(PAID))
                    .flatMap(Collection::stream)
                    .map(TransactionDetails::getMoney)
                    .reduce(Money.zero("USD"), Money::plus);
            cashCategory.setTotalPaidValue(totalPaidValue);
        };

        visit(categorizedInFlows, updateTotalPaid);
        visit(categorizedOutFlows, updateTotalPaid);
    }

    private void visit(List<CashCategory> cashCategories, Consumer<CashCategory> action) {
        Stack<CashCategory> stack = new Stack<>();
        cashCategories.forEach(stack::push);
        while (!stack.isEmpty()) {
            CashCategory takenCashCategory = stack.pop();
            action.accept(takenCashCategory);
            takenCashCategory.getSubCategories().forEach(stack::push);
        }
    }

    public record CashChangeLocation(CashChangeId cashChangeId, YearMonth yearMonth, Type type,
                                     Transaction transaction,
                                     CategoryName categoryName) {
    }

    /**
     * Status of a monthly forecast in the CashFlow lifecycle.
     * <p>
     * <b>State transitions:</b>
     * <pre>
     * Historical Import Flow:
     *   IMPORT_PENDING → IMPORTED (via attestHistoricalImport)
     *
     * Normal Monthly Flow:
     *   FORECASTED → ACTIVE (when month becomes current)
     *   ACTIVE → ROLLED_OVER (via automatic rollover or manual trigger)
     *   ACTIVE → ATTESTED (via attestMonth - DEPRECATED, use rollover instead)
     *
     * Gap Filling (Ongoing Sync):
     *   IMPORTED - allows gap filling (adding missed transactions)
     *   ROLLED_OVER - allows gap filling (adding missed transactions)
     * </pre>
     */
    public enum Status {
        /**
         * Historical month waiting for import (before attestation).
         * Created during createCashFlowWithHistory for months before activePeriod.
         * Allows importHistoricalCashChange operations.
         */
        IMPORT_PENDING,

        /**
         * Historical month with finalized imported data (after attestation).
         * Transitions from IMPORT_PENDING when attestHistoricalImport is called.
         * Allows gap filling - importing missed historical transactions.
         */
        IMPORTED,

        /**
         * Month closed through automatic rollover (scheduled job or manual trigger).
         * Transitions from ACTIVE at the beginning of the next month.
         * Allows gap filling - importing missed transactions from bank statements.
         * This is the preferred way to close months (replaces ATTESTED).
         */
        ROLLED_OVER,

        /**
         * Month closed through manual attestation (attestMonth).
         * Represents reconciled/finalized month data from regular usage.
         * @deprecated Use ROLLED_OVER instead. This status is kept for backward compatibility
         *             with existing data. New months should use automatic rollover.
         */
        @Deprecated
        ATTESTED,

        /**
         * Current month (the "now" month).
         * Only one month can have ACTIVE status at a time.
         * Allows normal operations: appendCashChange, confirmCashChange, etc.
         * Allows ongoing sync - importing transactions from bank statements.
         */
        ACTIVE,

        /**
         * Future month with projected/planned transactions.
         * Created automatically for upcoming months (typically 11 months ahead).
         * Allows adding expected transactions for planning purposes.
         * Does NOT allow importing transactions (blocked by validation).
         */
        FORECASTED
    }
}
