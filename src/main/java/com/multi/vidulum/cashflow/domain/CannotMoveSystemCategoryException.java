package com.multi.vidulum.cashflow.domain;

import lombok.Getter;

/**
 * Exception thrown when attempting to move a system category (e.g., "Uncategorized").
 * System categories cannot be moved to maintain application integrity.
 */
@Getter
public class CannotMoveSystemCategoryException extends RuntimeException {
    private final CategoryName categoryName;

    public CannotMoveSystemCategoryException(CategoryName categoryName) {
        super("Cannot move system category: " + categoryName.name());
        this.categoryName = categoryName;
    }
}
