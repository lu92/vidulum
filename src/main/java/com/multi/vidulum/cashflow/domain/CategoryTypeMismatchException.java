package com.multi.vidulum.cashflow.domain;

import lombok.Getter;

/**
 * Exception thrown when attempting to move a category to a parent of different type.
 * INFLOW categories can only have INFLOW parents, OUTFLOW categories can only have OUTFLOW parents.
 */
@Getter
public class CategoryTypeMismatchException extends RuntimeException {
    private final CategoryName categoryName;
    private final Type categoryType;
    private final CategoryName targetParentName;
    private final Type targetParentType;

    public CategoryTypeMismatchException(CategoryName categoryName, Type categoryType,
                                         CategoryName targetParentName, Type targetParentType) {
        super("Cannot move " + categoryType + " category [" + categoryName.name() + "] to "
                + targetParentType + " parent [" + targetParentName.name() + "]");
        this.categoryName = categoryName;
        this.categoryType = categoryType;
        this.targetParentName = targetParentName;
        this.targetParentType = targetParentType;
    }
}
