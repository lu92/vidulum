package com.multi.vidulum.recurring_rules.app.commands.update;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.recurring_rules.app.CashFlowCategoryValidator;
import com.multi.vidulum.recurring_rules.app.RecurringRuleExecutor;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDateRangeException;
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRuleStateException;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Changes a rule, and rebuilds the expected cash changes it had already generated.
 *
 * <p>{@code Void} rather than a result, which is the same choice cashflow makes for a command whose
 * answer is "it happened" - see {@code MoveCategoryCommandHandler}.
 *
 * <p>The order below is the order it was in: the rule must exist and be alive, then the category is
 * checked against the cash flow the rule already belongs to - <b>not</b> one named by the command,
 * which cannot move a rule between cash flows.
 */
@Slf4j
@Component
@AllArgsConstructor
public class UpdateRuleCommandHandler implements CommandHandler<UpdateRuleCommand, Void> {

    private final DomainRecurringRuleRepository ruleRepository;
    private final CashFlowCategoryValidator categoryValidator;
    private final RecurringRuleExecutor executor;
    private final Clock clock;

    @Override
    public Void handle(UpdateRuleCommand command) {
        RecurringRuleId ruleId = RecurringRuleId.of(command.ruleId());
        RecurringRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));

        if (rule.isDeleted()) {
            throw new InvalidRuleStateException(ruleId, rule.getStatus(), "update");
        }

        CashFlowId cashFlowId = rule.getCashFlowId();
        boolean isInflow = command.baseAmount().isPositive();
        categoryValidator.validate(cashFlowId, command.categoryName(), isInflow);

        if (command.endDate() != null && command.startDate().isAfter(command.endDate())) {
            throw new InvalidDateRangeException(command.startDate(), command.endDate());
        }

        if (command.maxOccurrences() != null && command.maxOccurrences() <= 0) {
            throw new IllegalArgumentException("maxOccurrences must be positive");
        }

        executor.clear(rule, command.authToken());

        rule.update(
                command.name(),
                command.description(),
                command.baseAmount(),
                command.categoryName(),
                command.pattern(),
                command.startDate(),
                command.endDate(),
                command.maxOccurrences(),
                command.activeMonths(),
                command.excludedDates(),
                clock
        );

        ruleRepository.save(rule);

        if (rule.isActive()) {
            executor.generate(rule, command.authToken());
        }

        log.info("Updated recurring rule {}", ruleId.id());
        return null;
    }
}
