package com.multi.vidulum.cashflow.app.commands.create;

import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;

public record CreateCashChangeCommand(
        UserId userId,
        Name name,
        Description description,
        Money money,
        Type type,
        ZonedDateTime dueDate
) implements Command {
}
