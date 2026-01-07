package com.multi.vidulum.cashflow.domain;

/**
 * Exception thrown when attempting to create a category with a name that already exists
 * and is currently active (not archived).
 * <p>
 * Multiple archived categories with the same name are allowed (different validity periods),
 * but only one active category with a given name can exist at any time.
 */
public class CategoryAlreadyExistsException extends RuntimeException {

    private final CategoryName categoryName;

    public CategoryAlreadyExistsException(CategoryName categoryName) {
        super(String.format(
                "Category [%s] already exists and is active. Archive the existing category first " +
                "to create a new one with the same name.",
                categoryName.name()));
        this.categoryName = categoryName;
    }

    public CategoryName getCategoryName() {
        return categoryName;
    }
}
