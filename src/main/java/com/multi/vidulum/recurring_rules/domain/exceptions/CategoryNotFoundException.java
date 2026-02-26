package com.multi.vidulum.recurring_rules.domain.exceptions;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;

/**
 * Thrown when a category is not found in the target cash flow.
 */
public class CategoryNotFoundException extends RecurringRuleException {

    private final CashFlowId cashFlowId;
    private final CategoryName categoryName;

    public CategoryNotFoundException(CashFlowId cashFlowId, CategoryName categoryName) {
        super(String.format("Category [%s] not found in CashFlow [%s]", categoryName.name(), cashFlowId.id()));
        this.cashFlowId = cashFlowId;
        this.categoryName = categoryName;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }

    public CategoryName getCategoryName() {
        return categoryName;
    }
}
