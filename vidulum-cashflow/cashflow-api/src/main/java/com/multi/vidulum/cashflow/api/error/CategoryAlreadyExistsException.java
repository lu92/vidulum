package com.multi.vidulum.cashflow.api.error;

import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Asked to create a category whose name is already taken by an active one.
 *
 * <p>Archived categories may share a name - they describe different validity periods - so this
 * fires only when an <i>active</i> category already holds it. Archive that one first to reuse the
 * name.
 *
 * <p>In the contract rather than in cashflow's domain, for the same reason
 * {@link CashFlowNotFoundException} is: a consumer catches it without importing anyone's domain
 * package. And like that one, it replaces a second class carrying the same meaning -
 * {@code CashFlowServiceClient.CategoryAlreadyExistsException} was a bare {@code RuntimeException}
 * with no {@link ErrorCode}, so {@code ErrorHttpHandler} did not map it and all four call sites had
 * to catch and translate. This one answers {@code 409} on its own.
 */
public class CategoryAlreadyExistsException extends BusinessException {

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

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CATEGORY_ALREADY_EXISTS;
    }
}
