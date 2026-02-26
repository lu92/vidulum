package com.multi.vidulum.recurring_rules.app.commands;

import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.LocalDate;

public record PauseRuleCommand(
        String ruleId,
        LocalDate resumeDate,
        String reason
) implements Command {
}
