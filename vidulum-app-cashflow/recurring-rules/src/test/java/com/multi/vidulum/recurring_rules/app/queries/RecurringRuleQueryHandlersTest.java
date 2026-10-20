package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.MonthlyPattern;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The three reads that answer with snapshots. They are small on purpose - a read that decides
 * nothing should look like it - so what is worth pinning is the one difference between them: when a
 * nothing-found is news and when it is an ordinary answer.
 */
class RecurringRuleQueryHandlersTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final UserId USER_ID = UserId.of("U10000001");
    private static final RecurringRuleId RULE_ID = RecurringRuleId.generate(1);
    private static final Instant NOW = Instant.parse("2022-01-01T00:00:00Z");

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);

    private final GetRuleQueryHandler oneRule = new GetRuleQueryHandler(rules);
    private final GetRulesByCashFlowQueryHandler byCashFlow = new GetRulesByCashFlowQueryHandler(rules);
    private final GetRulesByUserQueryHandler byUser = new GetRulesByUserQueryHandler(rules);

    @Test
    void oneRuleComesBackAsItsOwnSnapshot() {
        RecurringRule rule = rule("Rent");
        when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule));

        RecurringRuleSnapshot snapshot = oneRule.query(new GetRuleQuery(RULE_ID.id()));

        assertThat(snapshot)
                .usingRecursiveComparison()
                .isEqualTo(rule.getSnapshot());
    }

    @Test
    void askingForARuleThatIsNotThereIsNews() {
        when(rules.findById(RULE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> oneRule.query(new GetRuleQuery(RULE_ID.id())))
                .as("the id came from somewhere, so its absence is a 404 rather than an empty answer")
                .isInstanceOf(RuleNotFoundException.class);
    }

    @Test
    void everyRuleShapingOneCashFlowComesBackInOrderOfTheRepository() {
        when(rules.findByCashFlowId(CASH_FLOW_ID)).thenReturn(List.of(rule("Rent"), rule("Salary")));

        List<RecurringRuleSnapshot> snapshots = byCashFlow.query(new GetRulesByCashFlowQuery(CASH_FLOW_ID.id()));

        assertThat(snapshots).extracting(RecurringRuleSnapshot::name).containsExactly("Rent", "Salary");
    }

    @Test
    void aCashFlowWithNoRulesIsAnOrdinaryAnswer() {
        when(rules.findByCashFlowId(CASH_FLOW_ID)).thenReturn(List.of());

        assertThat(byCashFlow.query(new GetRulesByCashFlowQuery(CASH_FLOW_ID.id())))
                .as("a cash flow nobody has written a rule for is not an error")
                .isEmpty();
    }

    @Test
    void everyRuleAPersonOwnsComesBack() {
        when(rules.findByUserId(USER_ID)).thenReturn(List.of(rule("Rent")));

        assertThat(byUser.query(new GetRulesByUserQuery(USER_ID.getId())))
                .extracting(RecurringRuleSnapshot::name).containsExactly("Rent");
    }

    @Test
    void aPersonWithNoRulesIsAnOrdinaryAnswerToo() {
        when(rules.findByUserId(USER_ID)).thenReturn(List.of());

        assertThat(byUser.query(new GetRulesByUserQuery(USER_ID.getId()))).isEmpty();
    }

    private static RecurringRule rule(String name) {
        return RecurringRule.create(
                RULE_ID,
                USER_ID,
                CASH_FLOW_ID,
                name,
                "description",
                Money.of(-1500, "PLN"),
                new CategoryName("Housing"),
                new MonthlyPattern(15, 1, false),
                LocalDate.of(2022, 1, 1),
                null, null, null, null,
                NOW);
    }
}
