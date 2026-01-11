package com.multi.vidulum.cashflow.app.commands.comment.create;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.shared.cqrs.commands.Command;

public record CreateCategoryCommand(
        CashFlowId cashFlowId,
        CategoryName parentCategoryName,
        CategoryName categoryName,
        Type type,
        boolean isImportOperation
) implements Command {

    /**
     * Constructor for user-initiated category creation (blocks in SETUP mode).
     */
    public CreateCategoryCommand(CashFlowId cashFlowId, CategoryName parentCategoryName,
                                  CategoryName categoryName, Type type) {
        this(cashFlowId, parentCategoryName, categoryName, type, false);
    }

    /**
     * Factory method for import-initiated category creation (allowed in SETUP mode).
     */
    public static CreateCategoryCommand forImport(CashFlowId cashFlowId, CategoryName parentCategoryName,
                                                   CategoryName categoryName, Type type) {
        return new CreateCategoryCommand(cashFlowId, parentCategoryName, categoryName, type, true);
    }
}
