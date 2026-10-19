package com.multi.vidulum.recurring_rules.app.commands.create;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.CashFlowCategoryValidator;
import com.multi.vidulum.recurring_rules.app.ExpectedCashChangeSynchronizer;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDateRangeException;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Creates a recurring rule and the expected cash changes it implies for the forecast window.
 *
 * <p>The first command in this module to have a handler of its own, in the shape cashflow has used
 * since the beginning: one folder per command, the command and its handler side by side, reached
 * through {@code CommandGateway}. It was a method on {@code RecurringRuleService}, which had twelve
 * of them in 736 lines - so a reader looking for what creating a rule does had to find it among the
 * other eleven, and everything any of them needed was a field of the same class.
 */
@Slf4j
@Component
@AllArgsConstructor
public class CreateRuleCommandHandler implements CommandHandler<CreateRuleCommand, RecurringRuleId> {

    private final DomainRecurringRuleRepository ruleRepository;
    private final CashFlowCategoryValidator categoryValidator;
    private final ExpectedCashChangeSynchronizer cashChanges;
    private final Clock clock;

    @Override
    public RecurringRuleId handle(CreateRuleCommand command) {
        CashFlowId cashFlowId = CashFlowId.of(command.cashFlowId());

        // One question to cashflow's contract, answering two things: that the cash flow exists -
        // skeletonOf throws CashFlowNotFoundException by itself - and that the category does, on
        // the side this rule's sign implies.
        boolean isInflow = command.baseAmount().isPositive();
        categoryValidator.validate(cashFlowId, command.categoryName(), isInflow);

        if (command.endDate() != null && command.startDate().isAfter(command.endDate())) {
            throw new InvalidDateRangeException(command.startDate(), command.endDate());
        }

        if (command.maxOccurrences() != null && command.maxOccurrences() <= 0) {
            throw new IllegalArgumentException("maxOccurrences must be positive");
        }

        long sequence = ruleRepository.generateNextSequence();
        RecurringRuleId ruleId = RecurringRuleId.generate(sequence);

        RecurringRule rule = RecurringRule.create(
                ruleId,
                UserId.of(command.userId()),
                cashFlowId,
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

        cashChanges.generate(rule, command.authToken());

        log.info("Created recurring rule {} for CashFlow {}", ruleId.id(), cashFlowId.id());
        return ruleId;
    }
}
