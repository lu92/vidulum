package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.CategoryName;

/**
 * Exception thrown when attempting to unarchive a category while another category
 * with the same name is already active.
 * <p>
 * Only one category with a given name can be active (not archived) at any time.
 * To unarchive a previously archived category, the currently active one must be archived first,
 * or a new category with a different name should be created.
 * <p>
 * <b>Note:</b> Unarchive is primarily intended for accidental archive recovery.
 * If a user archived a category by mistake and there's no new active category with the same name,
 * they can unarchive to restore it. Once a new category with the same name is created,
 * the old archived category cannot be unarchived anymore.
 */
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class CannotUnarchiveCategoryException extends BusinessException {

    private final CategoryName categoryName;

    public CannotUnarchiveCategoryException(CategoryName categoryName) {
        super(String.format(
                "Cannot unarchive category [%s] because another category with the same name is already active. " +
                "Archive the active category first or use a different name.",
                categoryName.name()));
        this.categoryName = categoryName;
    }

    public CategoryName getCategoryName() {
        return categoryName;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CATEGORY_UNARCHIVE_CONFLICT;
    }
}
