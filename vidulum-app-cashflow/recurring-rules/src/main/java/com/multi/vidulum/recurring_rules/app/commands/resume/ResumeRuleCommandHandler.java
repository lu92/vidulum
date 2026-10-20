package com.multi.vidulum.recurring_rules.app.commands.resume;

import com.multi.vidulum.recurring_rules.app.RecurringRuleExecutor;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRuleStateException;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Puts a paused rule back to work, predicting from today rather than from where it left off.
 *
 * <p>The {@code clear} before the {@code generate} is deliberate even though a properly paused rule
 * has nothing left to clear: it makes resuming idempotent in the state a crash between the two
 * writes of {@link PauseRuleCommandHandler} would leave behind, and costs one call to say so.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ResumeRuleCommandHandler implements CommandHandler<ResumeRuleCommand, Void> {

    private final DomainRecurringRuleRepository ruleRepository;
    private final RecurringRuleExecutor executor;
    private final Clock clock;

    @Override
    public Void handle(ResumeRuleCommand command) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));

        if (!rule.isPaused()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "resume");
        }

        rule.resume(clock.instant());
        ruleRepository.save(rule);

        executor.clear(rule);
        executor.generate(rule);

        log.info("Resumed recurring rule {} (predictions regenerated from today)", ruleId.id());
        return null;
    }
}
