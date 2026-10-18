package com.multi.vidulum.cashflow.api.view;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.UserId;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How a cash flow is put together, without what is in it.
 *
 * <p>Eight fields. The aggregate's snapshot has sixteen, one of which is a map of every
 * transaction; this view exists so that asking "how is this cash flow organised" costs the same
 * whether the answer concerns a cash flow opened today or one with four years of imports behind it.
 *
 * <p>There is no direction field on a category and no {@code Type} anywhere in this contract.
 * Direction is which of the two lists a category is in - the only representation that cannot
 * contradict itself - and keeping it that way is also what frees this module from depending on
 * cashflow's domain enum, which 38 files of bank-data-ingestion share today.
 */
public record CashFlowSkeletonView(
        CashFlowId cashFlowId,
        UserId owner,
        LifecyclePhase phase,
        YearMonth activePeriod,
        YearMonth startPeriod,
        List<CategoryNodeView> inflowCategories,
        List<CategoryNodeView> outflowCategories) {

    /**
     * Where the cash flow is in its life.
     *
     * <p>The contract's own vocabulary, not the aggregate's nested {@code CashFlow.CashFlowStatus}.
     * Reusing that one would drag the aggregate into every consumer; and the vocabulary already
     * exists in three copies - in the aggregate, in the web DTO and in bank-data-ingestion's own
     * {@code CashFlowInfo} - so a fourth that is nobody's internal detail is the one worth having.
     */
    public enum LifecyclePhase {
        /** Historical import still running; the cash flow is not answering for money yet. */
        SETUP,
        /** In service. */
        OPEN,
        /** Finished; nothing new is appended. */
        CLOSED
    }

    public CashFlowSkeletonView {
        Objects.requireNonNull(cashFlowId, "cashFlowId is required");
        Objects.requireNonNull(owner, "owner is required");
        Objects.requireNonNull(phase, "phase is required");
        inflowCategories = inflowCategories == null ? List.of() : List.copyOf(inflowCategories);
        outflowCategories = outflowCategories == null ? List.of() : List.copyOf(outflowCategories);
    }

    public boolean isInSetup() {
        return phase == LifecyclePhase.SETUP;
    }

    public boolean isOpen() {
        return phase == LifecyclePhase.OPEN;
    }

    public boolean isClosed() {
        return phase == LifecyclePhase.CLOSED;
    }

    /**
     * Which dates this cash flow will accept an import for.
     *
     * <p>Three of this view's own fields are all the rule needs, which is why the rule can travel
     * with the view instead of being a question asked per row. See {@link ImportWindow} for what it
     * does and does not decide.
     */
    public ImportWindow importWindow() {
        return new ImportWindow(cashFlowId, phase, activePeriod, startPeriod);
    }

    /**
     * Every category name in the cash flow, subcategories included.
     *
     * <p>Typed, so that a name read out of a bank statement is not the same thing as a category
     * until someone says it is. The three call sites that compute this today do it over strings.
     */
    public Set<CategoryName> allCategoryNames() {
        return Stream.concat(inflowCategories.stream(), outflowCategories.stream())
                .flatMap(CategoryNodeView::flatten)
                .map(CategoryNodeView::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Names on the inflow side only - a cash flow may use one word for both directions. */
    public Set<CategoryName> inflowCategoryNames() {
        return namesOf(inflowCategories);
    }

    /** Names on the outflow side only. */
    public Set<CategoryName> outflowCategoryNames() {
        return namesOf(outflowCategories);
    }

    private static Set<CategoryName> namesOf(List<CategoryNodeView> roots) {
        return roots.stream()
                .flatMap(CategoryNodeView::flatten)
                .map(CategoryNodeView::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
