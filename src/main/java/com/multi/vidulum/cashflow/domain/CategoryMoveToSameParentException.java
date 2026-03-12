package com.multi.vidulum.cashflow.domain;

import lombok.Getter;

/**
 * Exception thrown when attempting to move a category to its current parent.
 * This is a no-op and indicates a client-side validation issue.
 */
@Getter
public class CategoryMoveToSameParentException extends RuntimeException {
    private final CategoryName categoryName;
    private final CategoryName parentName;

    public CategoryMoveToSameParentException(CategoryName categoryName, CategoryName parentName) {
        super("Category [" + categoryName.name() + "] is already under parent ["
                + (parentName.isDefined() ? parentName.name() : "root") + "]");
        this.categoryName = categoryName;
        this.parentName = parentName;
    }
}
