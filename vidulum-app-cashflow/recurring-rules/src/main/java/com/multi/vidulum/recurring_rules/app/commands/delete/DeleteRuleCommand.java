package com.multi.vidulum.recurring_rules.app.commands.delete;

import com.multi.vidulum.shared.cqrs.commands.Command;

public record DeleteRuleCommand(
        String ruleId,
        String reason
) implements Command {
}
