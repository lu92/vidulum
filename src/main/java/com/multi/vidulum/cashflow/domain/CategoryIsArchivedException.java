package com.multi.vidulum.cashflow.domain;

/**
 * Exception thrown when attempting to add a cash change to an archived category.
 * Archived categories are read-only and cannot accept new transactions.
 */
public class CategoryIsArchivedException extends RuntimeException {

    private final CategoryName categoryName;

    public CategoryIsArchivedException(CategoryName categoryName) {
        super(String.format("Cannot add cash change to archived category [%s]. Unarchive the category first or use a different category.", categoryName.name()));
        this.categoryName = categoryName;
    }

    public CategoryName getCategoryName() {
        return categoryName;
    }
}
