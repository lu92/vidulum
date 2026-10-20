package com.multi.vidulum.recurring_rules.app.commands.pause;

import com.multi.vidulum.recurring_rules.app.RecurringRuleExecutor;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.PauseInfo;
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
 * Stops a rule predicting, and takes back the predictions it had already made.
 *
 * <p>The withdrawal comes <b>before</b> the pause, and that order is the behaviour: a paused rule
 * that left its future cash changes behind would keep shaping someone's forecast while claiming to
 * be doing nothing.
 */
@Slf4j
@Component
@AllArgsConstructor
public class PauseRuleCommandHandler implements CommandHandler<PauseRuleCommand, Void> {

    private final DomainRecurringRuleRepository ruleRepository;
    private final RecurringRuleExecutor executor;
    private final Clock clock;

    @Override
    public Void handle(PauseRuleCommand command) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));

        if (!rule.isActive()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "pause");
        }

        executor.clear(rule);

        PauseInfo pauseInfo = command.resumeDate() != null
                ? PauseInfo.untilDate(clock.instant(), command.resumeDate(), command.reason())
                : PauseInfo.indefinite(clock.instant(), command.reason());

        rule.pause(pauseInfo, clock);
        ruleRepository.save(rule);

        log.info("Paused recurring rule {} until {} (withdrew its predictions)", ruleId.id(),
                command.resumeDate() != null ? command.resumeDate() : "indefinitely");
        return null;
    }
}
