package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.CategoryNodeView;
import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Cashflow answering its own contract, in this process.
 *
 * <p>Package-private: nobody outside this module names this class, they inject
 * {@link CashFlowModuleApi}. That is also what {@code ModuleBoundariesArchTest} requires of every
 * contract implementation.
 *
 * <p>Reads through the repository rather than the query gateway, because the one thing it answers
 * is a projection of the aggregate's own state and there is no query handler for it. When
 * bank-data-ingestion leaves this artifact, a second implementation will answer the same contract
 * over HTTP and this one stays as the local path; neither of them changes the contract.
 */
@Component
@AllArgsConstructor
class CashFlowModuleApiImpl implements CashFlowModuleApi {

    private final DomainCashFlowRepository repository;

    @Override
    public CashFlowSkeletonView skeletonOf(CashFlowId cashFlowId) {
        CashFlow cashFlow = repository.findById(cashFlowId)
                .orElseThrow(() -> new CashFlowNotFoundException(cashFlowId));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        return new CashFlowSkeletonView(
                snapshot.cashFlowId(),
                snapshot.userId(),
                phaseOf(snapshot),
                snapshot.activePeriod(),
                snapshot.startPeriod(),
                nodesOf(snapshot.inflowCategories(), null),
                nodesOf(snapshot.outflowCategories(), null));
    }

    @Override
    public boolean exists(CashFlowId cashFlowId) {
        // Loads the aggregate to answer a boolean, because DomainCashFlowRepository has no
        // existsById. Still one read against Mongo where the callers used to pay two HTTP requests
        // and a full payload; a narrower repository method is worth adding when something needs it
        // in a loop.
        return repository.findById(cashFlowId).isPresent();
    }

    /**
     * Maps the aggregate's nested status onto the contract's own vocabulary. A {@code switch} over
     * an enum rather than {@code valueOf}: adding a status to the aggregate should stop compiling
     * here, not surprise a consumer at runtime.
     */
    private static CashFlowSkeletonView.LifecyclePhase phaseOf(CashFlowSnapshot snapshot) {
        return switch (snapshot.status()) {
            case SETUP -> CashFlowSkeletonView.LifecyclePhase.SETUP;
            case OPEN -> CashFlowSkeletonView.LifecyclePhase.OPEN;
            case CLOSED -> CashFlowSkeletonView.LifecyclePhase.CLOSED;
        };
    }

    /**
     * The category tree, carrying each node's parent down with it - the one piece of redundancy the
     * view keeps, and the reason it keeps it is the flat lookups in categorisation.
     */
    private static List<CategoryNodeView> nodesOf(List<Category> categories, CategoryName parentName) {
        if (categories == null) {
            return List.of();
        }
        return categories.stream()
                .map(category -> new CategoryNodeView(
                        category.getCategoryName(),
                        parentName,
                        category.isArchived(),
                        nodesOf(category.getSubCategories(), category.getCategoryName())))
                .toList();
    }
}
