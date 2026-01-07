package com.multi.vidulum.cashflow.app.commands.archive;

import com.multi.vidulum.cashflow.domain.CategoryName;

/**
 * Exception thrown when attempting to archive a system category (e.g., "Uncategorized").
 * System categories cannot be archived as they are required for the application to function.
 */
public class CannotArchiveSystemCategoryException extends RuntimeException {

    private final CategoryName categoryName;

    public CannotArchiveSystemCategoryException(CategoryName categoryName) {
        super("Cannot archive system category: " + categoryName.name());
        this.categoryName = categoryName;
    }

    public CategoryName getCategoryName() {
        return categoryName;
    }
}
