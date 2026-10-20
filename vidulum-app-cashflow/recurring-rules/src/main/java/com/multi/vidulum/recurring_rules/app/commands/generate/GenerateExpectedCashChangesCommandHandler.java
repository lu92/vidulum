package com.multi.vidulum.recurring_rules.app.commands.generate;

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

/**
 * Rebuilds a rule's predictions for the forecast window from scratch.
 *
 * <p>It is the only one of these handlers that stores nothing itself: both halves of the work -
 * withdrawing what the rule had predicted and appending what it predicts now - belong to
 * {@link RecurringRuleExecutor}, which saves the rule after each. So this handler is the guard plus
 * two calls, and takes no {@code Clock}: it changes no state of its own that would need a stamp.
 *
 * <p>Only an ACTIVE rule regenerates. A paused one deliberately has no predictions
 * (see {@code PauseRuleCommandHandler}), and generating for it here would resurrect exactly what
 * pausing withdrew, without the rule ever leaving PAUSED.
 */
@Slf4j
@Component
@AllArgsConstructor
public class GenerateExpectedCashChangesCommandHandler
        implements CommandHandler<GenerateExpectedCashChangesCommand, Void> {

    private final DomainRecurringRuleRepository ruleRepository;
    private final RecurringRuleExecutor executor;

    @Override
    public Void handle(GenerateExpectedCashChangesCommand command) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));

        if (!rule.isActive()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "generate expected cash changes");
        }

        executor.clear(rule);
        executor.generate(rule);

        log.info("Regenerated expected cash changes for rule {}", ruleId.id());
        return null;
    }
}
