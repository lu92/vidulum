package com.multi.vidulum.cashflow.domain;

import lombok.Getter;

/**
 * Exception thrown when a category move would create a circular dependency.
 * A category cannot be moved to become a child of its own descendant.
 */
@Getter
public class CircularCategoryDependencyException extends RuntimeException {
    private final CategoryName categoryName;
    private final CategoryName targetParentName;

    public CircularCategoryDependencyException(CategoryName categoryName, CategoryName targetParentName) {
        super("Cannot move category [" + categoryName.name() + "] to [" + targetParentName.name()
                + "] - would create circular dependency");
        this.categoryName = categoryName;
        this.targetParentName = targetParentName;
    }
}
