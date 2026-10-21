package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.UpcomingTransactionsCalculator;
import com.multi.vidulum.recurring_rules.app.dto.DashboardResponse;
import com.multi.vidulum.recurring_rules.app.dto.UpcomingTransactionsResponse;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.ForecastWindow;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RuleStatus;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * The rules dashboard: how many rules by status, what the active ones add up to over the projection
 * period, and the next few transactions due.
 *
 * <p><b>Its projection period deliberately differs from {@link ForecastWindow}.</b> That window
 * starts <em>today</em>, because a rule does not predict backwards and an occurrence already past is
 * already in the cash flow. This one starts on the <em>first of the month</em>, because the number
 * it feeds is "what this month costs" - a figure that would be wrong on the 20th if it only counted
 * the remaining ten days. Same arithmetic, two different questions, so not one value.
 */
@Component
@AllArgsConstructor
public class GetDashboardQueryHandler implements QueryHandler<GetDashboardQuery, DashboardResponse> {

    private static final int UPCOMING_ON_THE_DASHBOARD = 5;
    private static final String DEFAULT_CURRENCY = "PLN";

    private final DomainRecurringRuleRepository ruleRepository;
    private final UpcomingTransactionsCalculator calculator;
    private final Clock clock;

    @Override
    public DashboardResponse query(GetDashboardQuery query) {
        UserId userId = UserId.of(query.userId());
        CashFlowId cashFlowId = CashFlowId.of(query.cashFlowId());

        YearMonth currentMonth = YearMonth.from(LocalDate.now(clock));
        LocalDate projectionStart = currentMonth.atDay(1);
        LocalDate projectionEnd = currentMonth.plusMonths(query.projectionMonths() - 1).atEndOfMonth();

        return DashboardResponse.builder()
                .userId(query.userId())
                .generatedAt(clock.instant())
                .cashFlowId(query.cashFlowId())
                .parameters(DashboardResponse.Parameters.builder()
                        .upcomingDays(query.upcomingDays())
                        .projectionMonths(query.projectionMonths())
                        .projectionPeriod(DashboardResponse.ProjectionPeriod.builder()
                                .startDate(projectionStart)
                                .endDate(projectionEnd)
                                .build())
                        .build())
                .summary(summaryOf(userId, cashFlowId))
                .monthlyProjection(projectionOf(userId, cashFlowId, projectionStart, projectionEnd))
                .upcomingTransactions(upcomingFor(userId, cashFlowId, query.upcomingDays()))
                .build();
    }

    private DashboardResponse.Summary summaryOf(UserId userId, CashFlowId cashFlowId) {
        return DashboardResponse.Summary.builder()
                .activeRulesCount(countOf(userId, cashFlowId, RuleStatus.ACTIVE))
                .pausedRulesCount(countOf(userId, cashFlowId, RuleStatus.PAUSED))
                .completedRulesCount(countOf(userId, cashFlowId, RuleStatus.COMPLETED))
                .build();
    }

    private int countOf(UserId userId, CashFlowId cashFlowId, RuleStatus status) {
        return (int) ruleRepository.countByUserIdAndCashFlowIdAndStatus(userId, cashFlowId, status);
    }

    /**
     * Income and expenses the active rules imply over the projection period, as two positive totals.
     *
     * <p>The currency is the one on the last occurrence seen (PLN when there are none) - the same
     * assumption the whole module makes, that a cash flow has one currency.
     */
    private DashboardResponse.MonthlyProjection projectionOf(
            UserId userId, CashFlowId cashFlowId, LocalDate from, LocalDate to) {
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal totalIncome = BigDecimal.ZERO;
        String currency = DEFAULT_CURRENCY;

        List<RecurringRule> activeRules =
                ruleRepository.findByUserIdAndCashFlowIdAndStatus(userId, cashFlowId, RuleStatus.ACTIVE);

        for (RecurringRule rule : activeRules) {
            for (LocalDate occurrence : rule.generateOccurrences(from, to)) {
                Money effectiveAmount = rule.calculateEffectiveAmount(occurrence);
                currency = effectiveAmount.getCurrency();

                if (effectiveAmount.isPositive()) {
                    totalIncome = totalIncome.add(effectiveAmount.getAmount());
                } else {
                    totalExpenses = totalExpenses.add(effectiveAmount.getAmount().abs());
                }
            }
        }

        return DashboardResponse.MonthlyProjection.builder()
                .expenses(Money.of(totalExpenses, currency))
                .income(Money.of(totalIncome, currency))
                .netBalance(Money.of(totalIncome.subtract(totalExpenses), currency))
                .build();
    }

    /**
     * The dashboard shows five, whatever {@code upcomingDays} was asked for - it is a summary, and
     * the full list has an endpoint of its own.
     */
    private List<DashboardResponse.UpcomingTransactionDto> upcomingFor(
            UserId userId, CashFlowId cashFlowId, int upcomingDays) {
        UpcomingTransactionsResponse upcoming =
                calculator.upcomingFor(userId, cashFlowId, upcomingDays, UPCOMING_ON_THE_DASHBOARD);

        return upcoming.getTransactions().stream()
                .map(transaction -> DashboardResponse.UpcomingTransactionDto.builder()
                        .ruleId(transaction.getRuleId())
                        .ruleName(transaction.getRuleName())
                        .cashChangeId(transaction.getCashChangeId())
                        .dueDate(transaction.getDueDate())
                        .amount(transaction.getAmount())
                        .type(transaction.getType())
                        .category(transaction.getCategory())
                        .daysUntilDue(transaction.getDaysUntilDue())
                        .build())
                .toList();
    }
}
