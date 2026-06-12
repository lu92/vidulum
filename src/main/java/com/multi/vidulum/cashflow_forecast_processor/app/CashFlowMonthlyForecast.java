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
    /**
     * Self-transfers (transfers between user's own bank accounts) bucketed separately
     * so they don't pollute budget aggregates. Routing is decided by the
     * {@code selfTransfer} flag carried on the CashFlowEvent / CashChange — see VID-161 Phase 1b.
     * <p>
     * Per VID-161 Q7: routing only, no separate stats — UI can sum these client-side.
     * Per VID-161 Q2: separate informational section, not excluded from snapshot.
     */
    private List<CashCategory> selfTransferInFlows;
    private List<CashCategory> selfTransferOutFlows;
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

    // ===== Self-transfer routing (VID-161 Phase 1b) =====
    // These methods place transactions into the dedicated self-transfer buckets and
    // intentionally DO NOT touch cashFlowStats.inflowStats / outflowStats — self-transfers
    // are out-of-budget by design (Q2 + Q7).

    public void addToSelfTransferInflows(CategoryName categoryName, Transaction transaction) {
        ensureSelfTransferListsInitialized();
        CashCategory cashCategory = findCategoryByCategoryName(categoryName, selfTransferInFlows)
                .orElseGet(() -> autoCreateSelfTransferCategory(categoryName, selfTransferInFlows,
                        transaction.transactionDetails().getMoney().getCurrency()));
        cashCategory.getGroupedTransactions().addTransaction(transaction);
    }

    public void removeFromSelfTransferInflows(CategoryName categoryName, Transaction transaction) {
        ensureSelfTransferListsInitialized();
        CashCategory cashCategory = findCategoryByCategoryName(categoryName, selfTransferInFlows).orElseThrow();
        cashCategory.getGroupedTransactions().removeTransaction(transaction);
    }

    public void addToSelfTransferOutflows(CategoryName categoryName, Transaction transaction) {
        ensureSelfTransferListsInitialized();
        CashCategory cashCategory = findCategoryByCategoryName(categoryName, selfTransferOutFlows)
                .orElseGet(() -> autoCreateSelfTransferCategory(categoryName, selfTransferOutFlows,
                        transaction.transactionDetails().getMoney().getCurrency()));
        cashCategory.getGroupedTransactions().addTransaction(transaction);
    }

    public void removeFromSelfTransferOutflows(CategoryName categoryName, Transaction transaction) {
        ensureSelfTransferListsInitialized();
        CashCategory cashCategory = findCategoryByCategoryName(categoryName, selfTransferOutFlows).orElseThrow();
        cashCategory.getGroupedTransactions().removeTransaction(transaction);
    }

    /**
     * VID-161 Phase 1b: lazy-creates the self-transfer category entry in the read model
     * the first time a transaction lands in it. Self-transfer categories are bucketed
     * separately from the regular categorizedIn/OutFlows (which are populated by
     * CategoryCreatedEvent), so the read model needs its own structure built on demand.
     */
    private CashCategory autoCreateSelfTransferCategory(CategoryName categoryName, List<CashCategory> list, String currency) {
        CashCategory newCategory = CashCategory.builder()
                .categoryName(categoryName)
                .category(new Category(categoryName.name()))
                .subCategories(new LinkedList<>())
                .groupedTransactions(new GroupedTransactions())
                .totalPaidValue(Money.zero(currency))
                .build();
        list.add(newCategory);
        return newCategory;
    }

    public Optional<CashCategory> findCategoryInSelfTransferInflowsByName(CategoryName categoryName) {
        return findCategoryByCategoryName(categoryName, selfTransferInFlows);
    }

    public Optional<CashCategory> findCategoryInSelfTransferOutflowsByName(CategoryName categoryName) {
        return findCategoryByCategoryName(categoryName, selfTransferOutFlows);
    }

    /**
     * Lazy initialization for Mongo-deserialized snapshots that may not have the new
     * VID-161 Phase 1b fields populated (pre-existing documents would have null lists).
     */
    private void ensureSelfTransferListsInitialized() {
        if (selfTransferInFlows == null) {
            selfTransferInFlows = new LinkedList<>();
        }
        if (selfTransferOutFlows == null) {
            selfTransferOutFlows = new LinkedList<>();
        }
    }

    public Optional<CashCategory> findSelfTransferCashCategoryForCashChange(CashChangeId cashChangeId, Type type) {
        List<CashCategory> source = Type.INFLOW.equals(type) ? selfTransferInFlows : selfTransferOutFlows;
        return findCashCategoryForCashChange(cashChangeId, source);
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
        if (cashCategories == null) {
            return Optional.empty();
        }
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
        String currency = cashFlowStats.getStart().getCurrency();

        Consumer<CashCategory> updateTotalPaid = cashCategory -> {
            Money totalPaidValue = flattenCategories(List.of(cashCategory)).stream()
                    .map(CashCategory::getGroupedTransactions)
                    .map(x -> x.get(PAID))
                    .flatMap(Collection::stream)
                    .map(TransactionDetails::getMoney)
                    .reduce(Money.zero(currency), Money::plus);
            cashCategory.setTotalPaidValue(totalPaidValue);
        };

        visit(categorizedInFlows, updateTotalPaid);
        visit(categorizedOutFlows, updateTotalPaid);
        if (selfTransferInFlows != null) {
            visit(selfTransferInFlows, updateTotalPaid);
        }
        if (selfTransferOutFlows != null) {
            visit(selfTransferOutFlows, updateTotalPaid);
        }
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
                                     CategoryName categoryName,
                                     boolean selfTransfer) {
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
