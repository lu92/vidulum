package com.multi.vidulum.cashflow.domain;

public class CategoryDoesNotExistsException extends RuntimeException {

    private CategoryName categoryName;

    public CategoryDoesNotExistsException(CategoryName categoryName) {
        super(String.format("Category [%s] does not exist", categoryName.name()));
        this.categoryName = categoryName;
    }
}
