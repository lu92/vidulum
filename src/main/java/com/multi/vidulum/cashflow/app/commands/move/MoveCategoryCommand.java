package com.multi.vidulum.cashflow.app.commands.move;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to move a category to a different parent (or to root level) and/or reorder within siblings.
 * <p>
 * All subcategories of the moved category will move with it, preserving the subtree structure.
 * Transactions associated with the moved categories remain unchanged.
 *
 * @param cashFlowId            the CashFlow containing the category
 * @param categoryName          the name of the category to move
 * @param newParentCategoryName the new parent category (use NOT_DEFINED to move to root level)
 * @param categoryType          whether this is an INFLOW or OUTFLOW category
 * @param position              the 0-based position among siblings (null = append at end)
 */
public record MoveCategoryCommand(
        CashFlowId cashFlowId,
        CategoryName categoryName,
        CategoryName newParentCategoryName,
        Type categoryType,
        Integer position
) implements Command {
}
