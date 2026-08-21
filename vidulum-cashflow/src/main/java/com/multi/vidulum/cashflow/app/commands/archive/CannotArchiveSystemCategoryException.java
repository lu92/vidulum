package com.multi.vidulum.cashflow.app.commands.archive;

import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Exception thrown when attempting to archive a system category (e.g., "Uncategorized").
 * System categories cannot be archived as they are required for the application to function.
 */
public class CannotArchiveSystemCategoryException extends BusinessException {

    private final CategoryName categoryName;

    public CannotArchiveSystemCategoryException(CategoryName categoryName) {
        super("Cannot archive system category: " + categoryName.name());
        this.categoryName = categoryName;
    }

    public CategoryName getCategoryName() {
        return categoryName;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CANNOT_ARCHIVE_SYSTEM_CATEGORY;
    }
}
