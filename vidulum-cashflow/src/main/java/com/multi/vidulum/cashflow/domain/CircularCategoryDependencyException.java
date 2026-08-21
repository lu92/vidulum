package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.CategoryName;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

/**
 * Exception thrown when a category move would create a circular dependency.
 * A category cannot be moved to become a child of its own descendant.
 */
@Getter
public class CircularCategoryDependencyException extends BusinessException {
    private final CategoryName categoryName;
    private final CategoryName targetParentName;

    public CircularCategoryDependencyException(CategoryName categoryName, CategoryName targetParentName) {
        super("Cannot move category [" + categoryName.name() + "] to [" + targetParentName.name()
                + "] - would create circular dependency");
        this.categoryName = categoryName;
        this.targetParentName = targetParentName;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CATEGORY_CIRCULAR_DEPENDENCY;
    }
}
