package com.multi.vidulum.recurring_rules.app.commands.removeamountchange;

import com.multi.vidulum.recurring_rules.app.RecurringRuleExecutor;
import com.multi.vidulum.recurring_rules.domain.AmountChangeId;
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
 * Takes back a raise, a cut or a one-off difference, putting the rule back on the amount it had
 * without it.
 *
 * <p>Mirror of {@link com.multi.vidulum.recurring_rules.app.commands.addamountchange.AddAmountChangeCommandHandler}:
 * the same states accept it, and the same ACTIVE-only rebuild follows. An amount change that is not
 * there is {@code AmountChangeNotFoundException} from the aggregate - a missing one is the caller's
 * mistake, not something to pass over in silence.
 */
@Slf4j
@Component
@AllArgsConstructor
public class RemoveAmountChangeCommandHandler implements CommandHandler<RemoveAmountChangeCommand, Void> {

    private final DomainRecurringRuleRepository ruleRepository;
    private final RecurringRuleExecutor executor;
    private final Clock clock;

    @Override
    public Void handle(RemoveAmountChangeCommand command) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));

        if (rule.isDeleted()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "remove amount change");
        }

        AmountChangeId changeId = AmountChangeId.of(command.amountChangeId());
        rule.removeAmountChange(changeId, clock.instant());
        ruleRepository.save(rule);

        if (rule.isActive()) {
            executor.clear(rule);
            executor.generate(rule);
        }

        log.info("Removed amount change {} from rule {}", changeId.id(), ruleId.id());
        return null;
    }
}
