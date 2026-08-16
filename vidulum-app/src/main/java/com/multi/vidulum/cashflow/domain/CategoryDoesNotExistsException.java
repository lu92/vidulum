package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class CategoryDoesNotExistsException extends BusinessException {

    private CategoryName categoryName;

    public CategoryDoesNotExistsException(CategoryName categoryName) {
        super(String.format("Category [%s] does not exist", categoryName.name()));
        this.categoryName = categoryName;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CATEGORY_NOT_FOUND;
    }
}
