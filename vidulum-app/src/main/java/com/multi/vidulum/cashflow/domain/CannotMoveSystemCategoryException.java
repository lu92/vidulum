package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

/**
 * Exception thrown when attempting to move a system category (e.g., "Uncategorized").
 * System categories cannot be moved to maintain application integrity.
 */
@Getter
public class CannotMoveSystemCategoryException extends BusinessException {
    private final CategoryName categoryName;

    public CannotMoveSystemCategoryException(CategoryName categoryName) {
        super("Cannot move system category: " + categoryName.name());
        this.categoryName = categoryName;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CANNOT_MOVE_SYSTEM_CATEGORY;
    }
}
