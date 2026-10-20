package com.multi.vidulum.recurring_rules.app.commands.delete;

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
 * Retires a rule and withdraws what it predicted.
 *
 * <p>Only the predictions go. A cash change the owner has already confirmed is a fact about their
 * money, and cashflow refuses to remove it - which is why
 * {@code CashFlowModuleApi.deleteExpected} skips rather than refuses, and why deleting a rule cannot
 * rewrite history.
 */
@Slf4j
@Component
@AllArgsConstructor
public class DeleteRuleCommandHandler implements CommandHandler<DeleteRuleCommand, Void> {

    private final DomainRecurringRuleRepository ruleRepository;
    private final RecurringRuleExecutor executor;
    private final Clock clock;

    @Override
    public Void handle(DeleteRuleCommand command) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));

        if (rule.isDeleted()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "delete");
        }

        executor.clear(rule);

        rule.delete(command.reason(), clock.instant());
        ruleRepository.save(rule);

        log.info("Deleted recurring rule {}", ruleId.id());
        return null;
    }
}
