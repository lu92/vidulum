package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.CategoryName;

/**
 * Exception thrown when attempting to add a cash change to an archived category.
 * Archived categories are read-only and cannot accept new transactions.
 */
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class CategoryIsArchivedException extends BusinessException {

    private final CategoryName categoryName;

    public CategoryIsArchivedException(CategoryName categoryName) {
        super(String.format("Cannot add cash change to archived category [%s]. Unarchive the category first or use a different category.", categoryName.name()));
        this.categoryName = categoryName;
    }

    public CategoryName getCategoryName() {
        return categoryName;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CATEGORY_IS_ARCHIVED;
    }
}
