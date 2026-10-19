package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.recurring_rules.domain.exceptions.CategoryNotFoundException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * A rule may only name a category its cash flow actually has, on the right side.
 *
 * <p>Both command handlers that create or change a rule ask this, and it is the only place in the
 * module that talks to {@link CashFlowModuleApi} - one request per command, answering two questions
 * at once: that the cash flow exists (the contract throws
 * {@link CashFlowNotFoundException} by itself) and that the category does.
 *
 * <p>It replaced a hand-rolled HTTP read that fetched the whole cash flow, every transaction
 * included, and pulled the category tree back out of a {@code Map<String, Object>} - naming four of
 * cashflow's JSON fields as string literals, so the producer could rename any of them and nothing
 * here would fail to compile.
 */
@Component
@AllArgsConstructor
public class CashFlowCategoryValidator {

    private final CashFlowModuleApi cashFlowApi;

    /**
     * <p>The name sets off the skeleton flatten subcategories, and {@link CategoryName} is a record,
     * so this is an exact-string comparison - the same one the hand-rolled version made. The
     * skeleton also offers a case-insensitive lookup; using it would change what this validation
     * accepts, so that is a separate decision.
     *
     * <p>Asked per side rather than over {@code allCategoryNames()} because the direction is part
     * of the question: a cash flow may use one word on both sides, and "Transfer" as an outflow is
     * not the category "Transfer" as an inflow.
     */
    public void validate(CashFlowId cashFlowId, CategoryName categoryName, boolean isInflow) {
        CashFlowSkeletonView skeleton = cashFlowApi.skeletonOf(cashFlowId);

        Set<CategoryName> categories = isInflow
                ? skeleton.inflowCategoryNames()
                : skeleton.outflowCategoryNames();

        if (!categories.contains(categoryName)) {
            throw new CategoryNotFoundException(cashFlowId, categoryName);
        }
    }
}
