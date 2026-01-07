package com.multi.vidulum.cashflow.app.commands.archive;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to archive a category, hiding it from new transactions while preserving historical data.
 *
 * @param cashFlowId           the CashFlow containing the category
 * @param categoryName         the name of the category to archive
 * @param categoryType         whether this is an INFLOW or OUTFLOW category
 * @param forceArchiveChildren if true, all subcategories will be archived as well;
 *                             if false and subcategories exist, the operation may fail or leave children unarchived
 */
public record ArchiveCategoryCommand(
        CashFlowId cashFlowId,
        CategoryName categoryName,
        Type categoryType,
        boolean forceArchiveChildren
) implements Command {
}
