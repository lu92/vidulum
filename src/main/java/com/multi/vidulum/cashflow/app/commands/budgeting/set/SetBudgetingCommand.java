package com.multi.vidulum.cashflow.app.commands.budgeting.set;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

public record SetBudgetingCommand(
        CashFlowId cashFlowId,
        CategoryName categoryName,
        Type categoryType,
        Money budget
) implements Command {
}
