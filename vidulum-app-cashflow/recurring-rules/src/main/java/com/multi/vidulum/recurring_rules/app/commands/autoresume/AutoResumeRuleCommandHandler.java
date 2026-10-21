package com.multi.vidulum.recurring_rules.app.commands.autoresume;

import com.multi.vidulum.recurring_rules.app.RecurringRuleExecutor;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * The scheduler's resume: the same writes as
 * {@code com.multi.vidulum.recurring_rules.app.commands.resume.ResumeRuleCommandHandler}, minus the
 * refusals.
 *
 * <p>A rule the job can no longer resume - deleted, or resumed by hand since the list was read - is
 * <b>skipped with a warning</b>. The scheduler reads a list and acts on it afterwards, so losing
 * that race is ordinary; throwing would make one stale row look like a failure of the night's work.
 *
 * <p><b>It regenerates the predictions.</b> Worth saying because it did not: the comment that stood
 * here said cash changes are not generated "because we don't have an auth token", which was true
 * while the writes went out over HTTP and left an auto-resumed rule ACTIVE but predicting nothing
 * until somebody edited it by hand (F61). The executor takes no token, so the reason is gone.
 */
@Slf4j
@Component
@AllArgsConstructor
public class AutoResumeRuleCommandHandler implements CommandHandler<AutoResumeRuleCommand, Void> {

    private final DomainRecurringRuleRepository ruleRepository;
    private final RecurringRuleExecutor executor;
    private final Clock clock;

    @Override
    public Void handle(AutoResumeRuleCommand command) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        Optional<RecurringRule> found = ruleRepository.findById(ruleId);

        if (found.isEmpty()) {
            log.warn("Auto-resume: rule {} not found, skipping", ruleId.id());
            return null;
        }

        RecurringRule rule = found.get();
        if (!rule.isPaused()) {
            log.warn("Auto-resume: rule {} is not paused (status: {}), skipping", ruleId.id(), rule.getStatus());
            return null;
        }

        rule.resume(clock.instant());
        ruleRepository.save(rule);

        executor.clear(rule);
        executor.generate(rule);

        log.info("Auto-resumed rule {} as of {} (predictions regenerated)", ruleId.id(), command.resumeDate());
        return null;
    }
}
