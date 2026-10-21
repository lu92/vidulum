package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.dto.UpcomingTransactionsResponse;
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
 * What the dashboard and the upcoming list both read. It had no test of its own while it was two
 * methods in a service - the dashboard pair was covered only end to end, which is a server, a Mongo
 * and a Kafka to assert a sort and a limit.
 */
class UpcomingTransactionsCalculatorTest {

    private static final UserId USER_ID = UserId.of("U10000001");
    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final Instant NOW = Instant.parse("2022-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final UpcomingTransactionsCalculator calculator =
            new UpcomingTransactionsCalculator(rules, FIXED_CLOCK);

    @Test
    void transactionsFromEveryActiveRuleComeBackSoonestFirst() {
        active(rule("Rent", Money.of(-1500, "PLN"), new MonthlyPattern(15, 1, false)),
               rule("Salary", Money.of(5000, "PLN"), new MonthlyPattern(5, 1, false)));

        UpcomingTransactionsResponse response = calculator.upcomingFor(USER_ID, CASH_FLOW_ID, 30, 20);

        assertThat(response.getTransactions())
                .extracting(UpcomingTransactionsResponse.UpcomingTransaction::getRuleName,
                        UpcomingTransactionsResponse.UpcomingTransaction::getDueDate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Salary", LocalDate.of(2022, 1, 5)),
                        org.assertj.core.groups.Tuple.tuple("Rent", LocalDate.of(2022, 1, 15)));
    }

    @Test
    void theSignLivesInTheTypeAndTheAmountIsPositive() {
        active(rule("Rent", Money.of(-1500, "PLN"), new MonthlyPattern(15, 1, false)),
               rule("Salary", Money.of(5000, "PLN"), new MonthlyPattern(5, 1, false)));

        UpcomingTransactionsResponse response = calculator.upcomingFor(USER_ID, CASH_FLOW_ID, 30, 20);

        assertThat(response.getTransactions())
                .extracting(UpcomingTransactionsResponse.UpcomingTransaction::getType,
                        UpcomingTransactionsResponse.UpcomingTransaction::getAmount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("INFLOW", Money.of(5000, "PLN")),
                        org.assertj.core.groups.Tuple.tuple("OUTFLOW", Money.of(1500, "PLN")));
    }

    @Test
    void eachTransactionSaysHowManyDaysAreLeft() {
        active(rule("Rent", Money.of(-1500, "PLN"), new MonthlyPattern(15, 1, false)));

        UpcomingTransactionsResponse response = calculator.upcomingFor(USER_ID, CASH_FLOW_ID, 30, 20);

        assertThat(response.getTransactions())
                .singleElement()
                .extracting(UpcomingTransactionsResponse.UpcomingTransaction::getDaysUntilDue)
                .isEqualTo(14);
    }

    @Test
    void onlyWhatFallsInsideTheWindowIsDue() {
        active(rule("Rent", Money.of(-1500, "PLN"), new MonthlyPattern(15, 1, false)));

        assertThat(calculator.upcomingFor(USER_ID, CASH_FLOW_ID, 7, 20).getTransactions())
                .as("the 15th is outside a seven-day window")
                .isEmpty();
        assertThat(calculator.upcomingFor(USER_ID, CASH_FLOW_ID, 14, 20).getTransactions())
                .as("today plus fourteen days reaches it")
                .hasSize(1);
    }

    /**
     * The quirk worth knowing before reading the numbers: the totals are summed after the limit, so
     * they describe the page, not the window. Preserved from the method this replaced - changing it
     * changes what the endpoint means.
     */
    @Test
    void theTotalsDescribeThePageRatherThanTheWindow() {
        active(rule("Coffee", Money.of(-10, "PLN"), new DailyPattern(1)));

        UpcomingTransactionsResponse limited = calculator.upcomingFor(USER_ID, CASH_FLOW_ID, 30, 5);

        assertThat(limited.getTransactions()).hasSize(5);
        assertThat(limited.getTotalCount()).isEqualTo(5);
        assertThat(limited.getTotalOutflow())
                .as("five shown rows at 10, not the thirty-one the window holds")
                .isEqualTo(Money.of(50, "PLN"));
        assertThat(limited.getNetChange()).isEqualTo(Money.of(-50, "PLN"));
    }

    @Test
    void aLimitOfZeroOrLessMeansNoLimit() {
        active(rule("Coffee", Money.of(-10, "PLN"), new DailyPattern(1)));

        assertThat(calculator.upcomingFor(USER_ID, CASH_FLOW_ID, 10, 0).getTransactions())
                .as("eleven days inclusive of today")
                .hasSize(11);
    }

    @Test
    void aPersonWithNoActiveRulesGetsZeroesInTheDefaultCurrency() {
        when(rules.findByUserIdAndCashFlowIdAndStatus(USER_ID, CASH_FLOW_ID, RuleStatus.ACTIVE))
                .thenReturn(List.of());

        UpcomingTransactionsResponse response = calculator.upcomingFor(USER_ID, CASH_FLOW_ID, 30, 20);

        assertThat(response.getTransactions()).isEmpty();
        assertThat(response.getTotalCount()).isZero();
        assertThat(response.getTotalInflow()).isEqualTo(Money.of(0, "PLN"));
        assertThat(response.getTotalOutflow()).isEqualTo(Money.of(0, "PLN"));
        assertThat(response.getNetChange()).isEqualTo(Money.of(0, "PLN"));
    }

    @Test
    void theCashChangeIdIsAlwaysAbsent() {
        active(rule("Rent", Money.of(-1500, "PLN"), new MonthlyPattern(15, 1, false)));

        assertThat(calculator.upcomingFor(USER_ID, CASH_FLOW_ID, 30, 20).getTransactions())
                .singleElement()
                .extracting(UpcomingTransactionsResponse.UpcomingTransaction::getCashChangeId)
                .as("the rule records the ids it generated but not which occurrence each belongs to, "
                        + "so there is nothing to look up - true before this moved, and still true")
                .isNull();
    }

    private void active(RecurringRule... activeRules) {
        when(rules.findByUserIdAndCashFlowIdAndStatus(USER_ID, CASH_FLOW_ID, RuleStatus.ACTIVE))
                .thenReturn(List.of(activeRules));
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
