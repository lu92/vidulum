package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

/**
 * Exception thrown when attempting to move a category between different types (INFLOW <-> OUTFLOW).
 * Categories cannot change their type once created.
 */
@Getter
public class CannotChangeCategoryTypeException extends BusinessException {
    private final CategoryName categoryName;
    private final Type currentType;
    private final Type requestedType;

    public CannotChangeCategoryTypeException(CategoryName categoryName, Type currentType, Type requestedType) {
        super(String.format("Cannot change category type for '%s' from %s to %s. Categories cannot be moved between INFLOW and OUTFLOW.",
                categoryName.name(), currentType, requestedType));
        this.categoryName = categoryName;
        this.currentType = currentType;
        this.requestedType = requestedType;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CANNOT_CHANGE_CATEGORY_TYPE;
    }
}
