package com.multi.vidulum.recurring_rules.app.commands.addamountchange;

import com.multi.vidulum.recurring_rules.app.RecurringRuleExecutor;
import com.multi.vidulum.recurring_rules.domain.AmountChange;
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
 * Records a raise, a cut or a one-off difference to what the rule asks for.
 *
 * <p>The amount change is recorded on a rule in any state but DELETED, <b>and only an ACTIVE rule's
 * predictions are rebuilt</b>: a paused rule holds none to rebuild, and regenerating would both
 * contradict the pause and leave cash changes nothing could later withdraw. Its new amount takes
 * effect when it resumes, which regenerates from today anyway.
 */
@Slf4j
@Component
@AllArgsConstructor
public class AddAmountChangeCommandHandler implements CommandHandler<AddAmountChangeCommand, AmountChangeId> {

    private final DomainRecurringRuleRepository ruleRepository;
    private final RecurringRuleExecutor executor;
    private final Clock clock;

    @Override
    public AmountChangeId handle(AddAmountChangeCommand command) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));

        if (rule.isDeleted()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "add amount change");
        }

        AmountChangeId changeId = AmountChangeId.generate(ruleRepository.generateNextAmountChangeSequence());

        AmountChange amountChange = new AmountChange(
                changeId,
                command.amount(),
                command.type(),
                command.reason()
        );

        rule.addAmountChange(amountChange, clock.instant());
        ruleRepository.save(rule);

        if (rule.isActive()) {
            executor.clear(rule);
            executor.generate(rule);
        }

        log.info("Added {} amount change {} to rule {}", command.type(), changeId.id(), ruleId.id());
        return changeId;
    }
}
