package com.multi.vidulum.cashflow.app.commands.append;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;

public record AppendPaidCashChangeCommand(
        CashFlowId cashFlowId,
        CategoryName categoryName,
        CashChangeId cashChangeId,
        Name name,
        Description description,
        Money money,
        Type type,
        ZonedDateTime created,
        ZonedDateTime dueDate,
        ZonedDateTime paidDate
) implements Command {
}
