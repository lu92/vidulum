package com.multi.vidulum.cashflow.app.commands.budgeting.remove;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.shared.cqrs.commands.Command;

public record RemoveBudgetingCommand(
        CashFlowId cashFlowId,
        CategoryName categoryName,
        Type categoryType
) implements Command {
}
