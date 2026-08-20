package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.CategoryName;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class BudgetingDoesNotExistsException extends BusinessException {

    private CategoryName categoryName;

    public BudgetingDoesNotExistsException(CategoryName categoryName) {
        super(String.format("Budgeting does not exist for category [%s]", categoryName.name()));
        this.categoryName = categoryName;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.BUDGETING_NOT_FOUND;
    }
}
