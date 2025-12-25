package com.multi.vidulum.cashflow.domain;

public class BudgetingAlreadyExistsException extends RuntimeException {

    private CategoryName categoryName;

    public BudgetingAlreadyExistsException(CategoryName categoryName) {
        super(String.format("Budgeting already exists for category [%s]", categoryName.name()));
        this.categoryName = categoryName;
    }
}
