package com.multi.vidulum.cashflow.app.commands.create;

import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;

public record CreateCashFlowCommand(
        UserId userId,
        Name name,
        Description description,
        Money balance) implements Command {
}
