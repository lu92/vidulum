package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.CategoryName;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class BudgetingAlreadyExistsException extends BusinessException {

    private CategoryName categoryName;

    public BudgetingAlreadyExistsException(CategoryName categoryName) {
        super(String.format("Budgeting already exists for category [%s]", categoryName.name()));
        this.categoryName = categoryName;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.BUDGETING_ALREADY_EXISTS;
    }
}
