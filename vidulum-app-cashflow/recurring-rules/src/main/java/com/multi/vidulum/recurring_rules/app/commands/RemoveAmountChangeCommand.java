package com.multi.vidulum.recurring_rules.app.commands;

import com.multi.vidulum.shared.cqrs.commands.Command;

public record RemoveAmountChangeCommand(
        String ruleId,
        String amountChangeId
) implements Command {
}
