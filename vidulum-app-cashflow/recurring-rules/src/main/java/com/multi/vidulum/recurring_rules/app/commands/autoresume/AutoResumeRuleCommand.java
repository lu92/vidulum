package com.multi.vidulum.recurring_rules.app.commands.autoresume;

import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.LocalDate;

/**
 * Puts a rule back to work because the date it was paused until has arrived.
 *
 * <p>Separate from {@code ResumeRuleCommand} on purpose, and not because the writes differ - they
 * are the same three - but because <b>nobody is waiting for the answer</b>. A person pressing resume
 * must be told when their rule cannot be resumed; a nightly job that lost a race must carry on with
 * the rest of the list. One refuses, the other skips with a warning, and a flag on one command would
 * have hidden that behind a boolean.
 *
 * <p>{@code resumeDate} travels with the command for the log only: the scheduler read it when it
 * built the list, and quoting it afterwards explains <em>why</em> a rule woke up on a given night.
 */
public record AutoResumeRuleCommand(
        String ruleId,
        LocalDate resumeDate
) implements Command {
}
