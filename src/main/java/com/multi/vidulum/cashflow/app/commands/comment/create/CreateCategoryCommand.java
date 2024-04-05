package com.multi.vidulum.cashflow.app.commands.comment.create;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.shared.cqrs.commands.Command;

public record CreateCategoryCommand(
        CashFlowId cashFlowId,
        CategoryName parentCategoryName,
        CategoryName categoryName,
        Type type
) implements Command {
}
