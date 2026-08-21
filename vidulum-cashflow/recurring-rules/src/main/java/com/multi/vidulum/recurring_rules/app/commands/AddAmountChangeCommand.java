package com.multi.vidulum.recurring_rules.app.commands;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.AmountChangeType;
import com.multi.vidulum.shared.cqrs.commands.Command;

public record AddAmountChangeCommand(
        String ruleId,
        Money amount,
        AmountChangeType type,
        String reason
) implements Command {
}
