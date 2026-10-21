package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.dto.UpcomingTransactionsResponse;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RuleStatus;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What a person's active rules imply over the next few days.
 *
 * <p><b>A collaborator, because two readers need it.</b> {@code GetUpcomingTransactionsQueryHandler}
 * answers with it directly and {@code GetDashboardQueryHandler} embeds the first few rows in its own
 * shape - and a handler must not reach for the gateway to ask another handler. This is the read-side
 * counterpart of {@link RecurringRuleExecutor}: the two dashboard methods used to live in one class
 * precisely because one called the other, which is the shape that kept them there.
 *
 * <p>Two behaviours preserved from that class, both worth knowing before reading the numbers:
 *
 * <ul>
 *   <li><b>The totals describe the page, not the window.</b> They are summed after {@code limit} is
 *       applied, so a dashboard showing 5 of 30 due transactions reports the inflow of those 5.</li>
 *   <li><b>The currency is taken from the last row</b> (PLN when there are none). For one cash flow
 *       in one currency - what the application assumes everywhere else - that is right; for mixed
 *       currencies the totals would be labelled with whichever row came last rather than converted.
 *       Kept as it was: changing it changes what the endpoint means, which is not a porting
 *       decision.</li>
 * </ul>
 */
@Component
@AllArgsConstructor
public class UpcomingTransactionsCalculator {

    private static final String DEFAULT_CURRENCY = "PLN";
    private static final String INFLOW = "INFLOW";
    private static final String OUTFLOW = "OUTFLOW";

    private final DomainRecurringRuleRepository ruleRepository;
    private final Clock clock;

    public UpcomingTransactionsResponse upcomingFor(UserId userId, CashFlowId cashFlowId, int days, int limit) {
        LocalDate today = LocalDate.now(clock);
        LocalDate endDate = today.plusDays(days);

        List<RecurringRule> activeRules =
                ruleRepository.findByUserIdAndCashFlowIdAndStatus(userId, cashFlowId, RuleStatus.ACTIVE);

        List<UpcomingTransactionsResponse.UpcomingTransaction> due = new ArrayList<>();
        for (RecurringRule rule : activeRules) {
            for (LocalDate occurrence : rule.generateOccurrences(today, endDate)) {
                due.add(transactionOf(rule, occurrence, today));
            }
        }

        List<UpcomingTransactionsResponse.UpcomingTransaction> soonestFirst = due.stream()
                .sorted(Comparator.comparing(UpcomingTransactionsResponse.UpcomingTransaction::getDueDate))
                .limit(limit > 0 ? limit : Integer.MAX_VALUE)
                .toList();

        return totalled(soonestFirst);
    }

    private static UpcomingTransactionsResponse.UpcomingTransaction transactionOf(
            RecurringRule rule, LocalDate occurrence, LocalDate today) {
        Money effectiveAmount = rule.calculateEffectiveAmount(occurrence);

        return UpcomingTransactionsResponse.UpcomingTransaction.builder()
                .ruleId(rule.getRuleId().id())
                .ruleName(rule.getName())
                // Always null, and it was before this moved: the rule records the ids it generated
                // but not which occurrence each belongs to, so there is nothing to look up. What
                // stood here was a private method whose whole body was "return null" behind a
                // comment calling itself a simplified implementation.
                .cashChangeId(null)
                .dueDate(occurrence)
                .amount(Money.of(effectiveAmount.getAmount().abs(), effectiveAmount.getCurrency()))
                .type(effectiveAmount.isPositive() ? INFLOW : OUTFLOW)
                .category(rule.getCategoryName().name())
                .daysUntilDue((int) ChronoUnit.DAYS.between(today, occurrence))
                .build();
    }

    private static UpcomingTransactionsResponse totalled(
            List<UpcomingTransactionsResponse.UpcomingTransaction> transactions) {
        BigDecimal totalInflow = BigDecimal.ZERO;
        BigDecimal totalOutflow = BigDecimal.ZERO;
        String currency = DEFAULT_CURRENCY;

        for (UpcomingTransactionsResponse.UpcomingTransaction transaction : transactions) {
            currency = transaction.getAmount().getCurrency();
            if (INFLOW.equals(transaction.getType())) {
                totalInflow = totalInflow.add(transaction.getAmount().getAmount());
            } else {
                totalOutflow = totalOutflow.add(transaction.getAmount().getAmount());
            }
        }

        return UpcomingTransactionsResponse.builder()
                .transactions(transactions)
                .totalInflow(Money.of(totalInflow, currency))
                .totalOutflow(Money.of(totalOutflow, currency))
                .netChange(Money.of(totalInflow.subtract(totalOutflow), currency))
                .totalCount(transactions.size())
                .build();
    }
}
