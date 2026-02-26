package com.multi.vidulum.recurring_rules.app.commands;

import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to generate expected cash changes for a recurring rule.
 * Generates for current month + 11 months (12 months total).
 */
public record GenerateExpectedCashChangesCommand(
        String ruleId
) implements Command {
}
