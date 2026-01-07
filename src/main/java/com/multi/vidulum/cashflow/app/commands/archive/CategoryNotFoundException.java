package com.multi.vidulum.cashflow.app.commands.archive;

import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;

/**
 * Exception thrown when a category is not found in the CashFlow.
 */
public class CategoryNotFoundException extends RuntimeException {

    private final CategoryName categoryName;
    private final Type categoryType;

    public CategoryNotFoundException(CategoryName categoryName, Type categoryType) {
        super(String.format("Category [%s] of type [%s] not found", categoryName.name(), categoryType));
        this.categoryName = categoryName;
        this.categoryType = categoryType;
    }

    public CategoryName getCategoryName() {
        return categoryName;
    }

    public Type getCategoryType() {
        return categoryType;
    }
}
