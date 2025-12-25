package com.multi.vidulum.cashflow.domain;

public class BudgetingDoesNotExistsException extends RuntimeException {

    private CategoryName categoryName;

    public BudgetingDoesNotExistsException(CategoryName categoryName) {
        super(String.format("Budgeting does not exist for category [%s]", categoryName.name()));
        this.categoryName = categoryName;
    }
}
