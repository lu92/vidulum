package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.UpcomingTransactionsCalculator;
import com.multi.vidulum.recurring_rules.app.dto.DashboardResponse;
import com.multi.vidulum.recurring_rules.domain.DailyPattern;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.MonthlyPattern;
import com.multi.vidulum.recurring_rules.domain.RecurrencePattern;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RuleStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dashboard. Its numbers came from a single 86-line method with no test of its own, so these are
 * the first assertions on the projection period, the summary counts and the five-row cap.
 */
class GetDashboardQueryHandlerTest {

    private static final UserId USER_ID = UserId.of("U10000001");
    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final Instant NOW = Instant.parse("2022-01-20T09:30:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);

    private final GetDashboardQueryHandler handler = new GetDashboardQueryHandler(
            rules, new UpcomingTransactionsCalculator(rules, FIXED_CLOCK), FIXED_CLOCK);

    /**
     * The projection period starts on the <b>first of the month</b>, not today - the number it feeds
     * answers "what does this month cost", which would be wrong on the 20th if it counted only the
     * remaining eleven days. {@code ForecastWindow} starts today because it answers a different
     * question.
     */
    @Test
    void theProjectionPeriodCoversWholeMonthsFromTheFirst() {
        noRules();

        DashboardResponse response = handler.query(new GetDashboardQuery(
                USER_ID.getId(), CASH_FLOW_ID.id(), 7, 3));

        DashboardResponse.ProjectionPeriod period = response.getParameters().getProjectionPeriod();
        assertThat(period.getStartDate())
                .as("the dashboard is asked on the 20th and still reports the whole month")
                .isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(period.getEndDate()).isEqualTo(LocalDate.of(2022, 3, 31));
        assertThat(response.getParameters().getUpcomingDays()).isEqualTo(7);
        assertThat(response.getParameters().getProjectionMonths()).isEqualTo(3);
    }

    @Test
    void theSummaryCountsRulesByStatusForThisCashFlowOnly() {
        noRules();
        when(rules.countByUserIdAndCashFlowIdAndStatus(USER_ID, CASH_FLOW_ID, RuleStatus.ACTIVE)).thenReturn(4L);
        when(rules.countByUserIdAndCashFlowIdAndStatus(USER_ID, CASH_FLOW_ID, RuleStatus.PAUSED)).thenReturn(2L);
        when(rules.countByUserIdAndCashFlowIdAndStatus(USER_ID, CASH_FLOW_ID, RuleStatus.COMPLETED)).thenReturn(1L);

        DashboardResponse.Summary summary = handler.query(new GetDashboardQuery(
                USER_ID.getId(), CASH_FLOW_ID.id(), 7, 1)).getSummary();

        assertThat(summary.getActiveRulesCount()).isEqualTo(4);
        assertThat(summary.getPausedRulesCount()).isEqualTo(2);
        assertThat(summary.getCompletedRulesCount()).isEqualTo(1);
    }

    @Test
    void incomeAndExpensesAreTwoPositiveTotalsAndTheirDifference() {
        activeRules(
                rule("Salary", Money.of(5000, "PLN"), new MonthlyPattern(5, 1, false)),
                rule("Rent", Money.of(-1500, "PLN"), new MonthlyPattern(15, 1, false)));

        DashboardResponse.MonthlyProjection projection = handler.query(new GetDashboardQuery(
                USER_ID.getId(), CASH_FLOW_ID.id(), 7, 1)).getMonthlyProjection();

        assertThat(projection.getIncome()).isEqualTo(Money.of(5000, "PLN"));
        assertThat(projection.getExpenses())
                .as("expenses are positive; the direction is the field's name")
                .isEqualTo(Money.of(1500, "PLN"));
        assertThat(projection.getNetBalance()).isEqualTo(Money.of(3500, "PLN"));
    }

    @Test
    void theProjectionSpansEveryMonthAskedFor() {
        activeRules(rule("Rent", Money.of(-1500, "PLN"), new MonthlyPattern(15, 1, false)));

        DashboardResponse threeMonths = handler.query(new GetDashboardQuery(
                USER_ID.getId(), CASH_FLOW_ID.id(), 7, 3));

        assertThat(threeMonths.getMonthlyProjection().getExpenses())
                .as("three occurrences of 1500 inside January-March")
                .isEqualTo(Money.of(4500, "PLN"));
    }

    /**
     * The dashboard shows five, whatever window was asked for: it is a summary, and the full list
     * has an endpoint of its own.
     */
    @Test
    void theUpcomingSectionIsCappedAtFiveRows() {
        activeRules(rule("Coffee", Money.of(-10, "PLN"), new DailyPattern(1)));

        DashboardResponse response = handler.query(new GetDashboardQuery(
                USER_ID.getId(), CASH_FLOW_ID.id(), 30, 1));

        assertThat(response.getUpcomingTransactions()).hasSize(5);
        assertThat(response.getUpcomingTransactions())
                .extracting(DashboardResponse.UpcomingTransactionDto::getDueDate)
                .containsExactly(
                        LocalDate.of(2022, 1, 20), LocalDate.of(2022, 1, 21), LocalDate.of(2022, 1, 22),
                        LocalDate.of(2022, 1, 23), LocalDate.of(2022, 1, 24));
    }

    @Test
    void itSaysWhoAskedWhenAndAboutWhichCashFlow() {
        noRules();

        DashboardResponse response = handler.query(new GetDashboardQuery(
                USER_ID.getId(), CASH_FLOW_ID.id(), 7, 1));

        assertThat(response.getUserId()).isEqualTo(USER_ID.getId());
        assertThat(response.getCashFlowId()).isEqualTo(CASH_FLOW_ID.id());
        assertThat(response.getGeneratedAt()).isEqualTo(NOW);
    }

    @Test
    void aCashFlowWithoutRulesProjectsZeroes() {
        noRules();

        DashboardResponse response = handler.query(new GetDashboardQuery(
                USER_ID.getId(), CASH_FLOW_ID.id(), 7, 1));

        assertThat(response.getMonthlyProjection().getIncome()).isEqualTo(Money.of(0, "PLN"));
        assertThat(response.getMonthlyProjection().getExpenses()).isEqualTo(Money.of(0, "PLN"));
        assertThat(response.getUpcomingTransactions()).isEmpty();
    }

    private void noRules() {
        activeRules();
    }

    private void activeRules(RecurringRule... active) {
        when(rules.findByUserIdAndCashFlowIdAndStatus(USER_ID, CASH_FLOW_ID, RuleStatus.ACTIVE))
                .thenReturn(List.of(active));
    }

    private static RecurringRule rule(String name, Money baseAmount, RecurrencePattern pattern) {
        return RecurringRule.create(
                RecurringRuleId.generate(Math.abs(name.hashCode() % 1000) + 1),
                USER_ID,
                CASH_FLOW_ID,
                name,
                "description",
                baseAmount,
                new CategoryName("Housing"),
                pattern,
                LocalDate.of(2022, 1, 1),
                null, null, null, null,
                NOW);
    }
}
