package com.multi.vidulum.recurring_rules.app.commands;

import com.multi.vidulum.shared.cqrs.commands.Command;

public record ResumeRuleCommand(
        String ruleId
) implements Command {
}
