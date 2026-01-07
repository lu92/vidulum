package com.multi.vidulum.cashflow.app.commands.archive;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to unarchive a category, making it available for new transactions again.
 *
 * @param cashFlowId   the CashFlow containing the category
 * @param categoryName the name of the category to unarchive
 * @param categoryType whether this is an INFLOW or OUTFLOW category
 */
public record UnarchiveCategoryCommand(
        CashFlowId cashFlowId,
        CategoryName categoryName,
        Type categoryType
) implements Command {
}
