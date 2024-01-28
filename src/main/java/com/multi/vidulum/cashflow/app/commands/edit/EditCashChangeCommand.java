package com.multi.vidulum.cashflow.app.commands.edit;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;

public record EditCashChangeCommand(
        CashFlowId cashFlowId,
        CashChangeId cashChangeId,
        Name name,
        Description description,
        Money money,
        ZonedDateTime dueDate) implements Command {
}
