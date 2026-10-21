package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.cashflow.api.CashChangeCountsByStatus;
import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.dto.DeleteImpactPreviewResponse;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.ForecastWindow;
import com.multi.vidulum.recurring_rules.domain.MonthlyPattern;
import com.multi.vidulum.recurring_rules.domain.PauseInfo;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The screen that answers "what do I lose if I delete this rule".
 *
 * <p>It had no test of its own: the only coverage was an integration case asserting
 * {@code pending() >= 0} and {@code confirmed() >= 0} - tautologies that pass with the numbers
 * hard-coded to zero. These are the exact numbers.
 */
class PreviewDeleteImpactQueryHandlerTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final RecurringRuleId RULE_ID = RecurringRuleId.generate(1);
    private static final Instant NOW = Instant.parse("2022-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final CashFlowModuleApi cashFlowApi = mock(CashFlowModuleApi.class);

    private final PreviewDeleteImpactQueryHandler handler =
            new PreviewDeleteImpactQueryHandler(rules, cashFlowApi, FIXED_CLOCK);

    @Test
    void aRuleThatDoesNotExistHasNoImpactToPreview() {
        when(rules.findById(RULE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.query(new PreviewDeleteImpactQuery(RULE_ID.id())))
                .isInstanceOf(RuleNotFoundException.class);

        verifyNoInteractions(cashFlowApi);
    }

    /**
     * The window is {@link ForecastWindow}'s, not one this handler invents - a monthly rule starting
     * in January gives twelve occurrences, the same twelve the executor would have generated.
     */
    @Test
    void anActiveRuleLosesEveryOccurrenceInTheForecastWindow() {
        RecurringRule rule = storedActiveRule();
        noCashChangesRecorded();

        DeleteImpactPreviewResponse response = handler.query(new PreviewDeleteImpactQuery(RULE_ID.id()));

        DeleteImpactPreviewResponse.FutureOccurrences future = response.impact().futureOccurrences();
        assertThat(future.count()).isEqualTo(12);
        assertThat(future.totalAmount()).isEqualTo(Money.of(18000, "PLN"));
        assertThat(future.dateRange().from()).isEqualTo(LocalDate.of(2022, 1, 15));
        assertThat(future.dateRange().to())
                .as("the last occurrence inside ForecastWindow, not the window's own end")
                .isEqualTo(LocalDate.of(2022, 12, 15));
        assertThat(response.impact().forecastImpact().affectedMonths())
                .containsExactly("2022-01", "2022-02", "2022-03", "2022-04", "2022-05", "2022-06",
                        "2022-07", "2022-08", "2022-09", "2022-10", "2022-11", "2022-12");
        assertThat(response.impact().forecastImpact().balanceReduction()).isEqualTo(Money.of(18000, "PLN"));
    }

    @Test
    void aPausedRuleHasNothingInTheForecastToLose() {
        RecurringRule rule = storedActiveRule();
        rule.pause(PauseInfo.indefinite(NOW, "holiday"), NOW);
        noCashChangesRecorded();

        DeleteImpactPreviewResponse response = handler.query(new PreviewDeleteImpactQuery(RULE_ID.id()));

        assertThat(response.impact().futureOccurrences().count()).isZero();
        assertThat(response.impact().futureOccurrences().dateRange())
                .as("no occurrences means no range, rather than a range of nothing")
                .isNull();
        assertThat(response.impact().forecastImpact().affectedMonths()).isEmpty();
        assertThat(response.impact().futureOccurrences().totalAmount()).isEqualTo(Money.of(0, "PLN"));
    }

    /**
     * The number this screen exists to show: what the owner has confirmed stays, so only the pending
     * ones are deletable.
     */
    @Test
    void whatTheOwnerConfirmedIsCountedAsStayingAndIsNotDeletable() {
        RecurringRule rule = storedActiveRule();
        recorded(rule, 12);
        when(cashFlowApi.countCashChangesByStatus(CASH_FLOW_ID, RULE_ID.id()))
                .thenReturn(new CashChangeCountsByStatus(9, 2, 1));

        DeleteImpactPreviewResponse response = handler.query(new PreviewDeleteImpactQuery(RULE_ID.id()));

        DeleteImpactPreviewResponse.GeneratedTransactions generated = response.impact().generatedTransactions();
        assertThat(generated.total()).isEqualTo(12);
        assertThat(generated.pending()).isEqualTo(9);
        assertThat(generated.confirmed()).isEqualTo(2);
        assertThat(generated.deletable())
                .as("a confirmed cash change is a fact about the owner's money; deleting a rule does not remove it")
                .isEqualTo(9);
        assertThat(response.warnings())
                .extracting(DeleteImpactPreviewResponse.Warning::type)
                .contains("CONFIRMED_TRANSACTIONS");
        assertThat(response.recommendations())
                .contains("Potwierdzone transakcje pozostaną w CashFlow jako historyczne dane");
    }

    /**
     * A rule that generated nothing has nothing to count, and asking anyway would be one call per
     * preview for a guaranteed zero.
     */
    @Test
    void aRuleThatGeneratedNothingDoesNotAskCashflowAtAll() {
        storedActiveRule();

        DeleteImpactPreviewResponse response = handler.query(new PreviewDeleteImpactQuery(RULE_ID.id()));

        verifyNoInteractions(cashFlowApi);
        assertThat(response.impact().generatedTransactions().total()).isZero();
        assertThat(response.impact().generatedTransactions().pending()).isZero();
        assertThat(response.warnings())
                .extracting(DeleteImpactPreviewResponse.Warning::type)
                .doesNotContain("CONFIRMED_TRANSACTIONS");
    }

    @Test
    void aRuleWorthMoreThanTenThousandWarnsAboutIt() {
        storedActiveRule();
        noCashChangesRecorded();

        DeleteImpactPreviewResponse response = handler.query(new PreviewDeleteImpactQuery(RULE_ID.id()));

        assertThat(response.warnings())
                .extracting(DeleteImpactPreviewResponse.Warning::type,
                        DeleteImpactPreviewResponse.Warning::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("HIGH_VALUE", "WARNING"));
        assertThat(response.recommendations())
                .as("twelve occurrences is more than a handful, so pausing is worth suggesting")
                .contains("Rozważ wstrzymanie reguły zamiast usuwania, jeśli planujesz ją reaktywować w przyszłości");
    }

    @Test
    void aSmallRuleWarnsAboutNothing() {
        RecurringRule rule = storedRule(Money.of(-100, "PLN"), LocalDate.of(2022, 12, 1));
        noCashChangesRecorded();

        DeleteImpactPreviewResponse response = handler.query(new PreviewDeleteImpactQuery(RULE_ID.id()));

        assertThat(response.impact().futureOccurrences().count()).isEqualTo(1);
        assertThat(response.warnings()).isEmpty();
        assertThat(response.recommendations())
                .as("one occurrence is not worth suggesting a pause over")
                .isEmpty();
        assertThat(response.ruleId()).isEqualTo(RULE_ID.id());
        assertThat(response.ruleName()).isEqualTo("Rent");
    }

    private void noCashChangesRecorded() {
        when(cashFlowApi.countCashChangesByStatus(any(), anyString()))
                .thenThrow(new AssertionError("nothing was generated, so nothing should be counted"));
    }

    private static void recorded(RecurringRule rule, int count) {
        List<CashChangeId> ids = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> CashChangeId.of("CC%010d".formatted(1_000_000_000L + i)))
                .toList();
        rule.recordGeneratedCashChanges(ids, LocalDate.of(2022, 1, 1), LocalDate.of(2022, 12, 31), NOW);
    }

    private RecurringRule storedActiveRule() {
        return storedRule(Money.of(-1500, "PLN"), LocalDate.of(2022, 1, 1));
    }

    private RecurringRule storedRule(Money baseAmount, LocalDate startDate) {
        RecurringRule rule = RecurringRule.create(
                RULE_ID,
                UserId.of("U10000001"),
                CASH_FLOW_ID,
                "Rent",
                "monthly rent",
                baseAmount,
                new CategoryName("Housing"),
                new MonthlyPattern(15, 1, false),
                startDate,
                null, null, null, null,
                NOW);
        when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule));
        return rule;
    }
}
