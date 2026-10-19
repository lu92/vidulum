package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.ExpectedCashChange;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.MonthlyPattern;
import com.multi.vidulum.recurring_rules.domain.RecurrencePattern;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.infrastructure.CashFlowHttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Turning a rule into the cash changes it implies.
 *
 * <p>This class had no test of its own until now - what covered it was the end-to-end rule tests,
 * which need a server, a Mongo and a Kafka to say anything about a loop over dates. It is testable
 * cheaply now for the same reason the change was worth making: the work goes through a contract that
 * can be stubbed, instead of an HTTP client calling the application's own endpoint.
 */
class RecurringRuleExecutorTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final CashFlowModuleApi cashFlowApi = mock(CashFlowModuleApi.class);
    private final CashFlowHttpClient httpClient = mock(CashFlowHttpClient.class);

    private final RecurringRuleExecutor executor =
            new RecurringRuleExecutor(rules, cashFlowApi, httpClient, FIXED_CLOCK);

    /**
     * The claim the change was made for. Twelve occurrences, one call - and counting the calls is
     * the only assertion that can tell the batch apart from the loop it replaced.
     */
    @Test
    void aYearOfOccurrencesIsOneCallToTheContract() {
        idsReserved(12);
        RecurringRule rule = monthlyRule(Money.of(-1500, "PLN"), null);

        executor.generate(rule);

        verify(cashFlowApi, times(1)).appendExpected(any(), anyString(), any());
        assertThat(appendedChanges()).hasSize(12);
        verifyNoInteractions(httpClient);
    }

    @Test
    void everyOccurrenceCarriesTheRulesOwnDetailsAndItsOwnDueDate() {
        idsReserved(12);
        RecurringRule rule = monthlyRule(Money.of(-1500, "PLN"), null);

        executor.generate(rule);

        List<ExpectedCashChange> changes = appendedChanges();
        assertThat(changes).allSatisfy(change -> {
            assertThat(change.categoryName()).isEqualTo(new CategoryName("Housing"));
            assertThat(change.name()).isEqualTo("Rent");
            assertThat(change.description()).isEqualTo("monthly rent");
            assertThat(change.type())
                    .as("the sign lives in the type, so the amount itself is positive")
                    .isEqualTo(Type.OUTFLOW);
            assertThat(change.money()).isEqualTo(Money.of(1500, "PLN"));
        });

        assertThat(changes).extracting(ExpectedCashChange::dueDate)
                .startsWith(LocalDate.of(2022, 1, 15).atStartOfDay(ZoneOffset.UTC))
                .endsWith(LocalDate.of(2022, 12, 15).atStartOfDay(ZoneOffset.UTC));
    }

    @Test
    void aPositiveAmountIsAnInflow() {
        idsReserved(12);

        executor.generate(monthlyRule(Money.of(5000, "PLN"), null));

        assertThat(appendedChanges()).allSatisfy(change -> {
            assertThat(change.type()).isEqualTo(Type.INFLOW);
            assertThat(change.money()).isEqualTo(Money.of(5000, "PLN"));
        });
    }

    @Test
    void theIdsTheContractAnsweredWithAreRecordedOnTheRule() {
        idsReserved(12);
        RecurringRule rule = monthlyRule(Money.of(-1500, "PLN"), null);

        executor.generate(rule);

        assertThat(rule.getGeneratedCashChangeIds())
                .as("and clear() works from exactly this list, so losing it orphans the cash changes")
                .hasSize(12)
                .containsExactlyElementsOf(reservedIds(12));
        verify(rules).save(rule);
    }

    /**
     * A rule whose occurrences all fall outside the forecast window has nothing to append, and must
     * not spend a call finding that out.
     */
    @Test
    void aRuleWithNoOccurrencesInTheWindowAsksForNothing() {
        RecurringRule rule = RecurringRule.create(
                RecurringRuleId.generate(1),
                UserId.of("U10000001"),
                CASH_FLOW_ID,
                "Rent",
                "monthly rent",
                Money.of(-1500, "PLN"),
                new CategoryName("Housing"),
                new MonthlyPattern(15, 1, false),
                LocalDate.of(2030, 6, 1),
                null, null, null, null,
                FIXED_CLOCK);

        executor.generate(rule);

        verifyNoInteractions(cashFlowApi);
        verify(rules, never()).save(any());
    }

    @Test
    void aRuleThatReachesItsOccurrenceLimitCompletesItself() {
        idsReserved(3);
        RecurringRule rule = monthlyRule(Money.of(-1500, "PLN"), 3);

        executor.generate(rule);

        assertThat(appendedChanges()).hasSize(3);
        assertThat(rule.getStatus())
                .as("the limit was reached, so the rule has nothing left to do")
                .isEqualTo(com.multi.vidulum.recurring_rules.domain.RuleStatus.COMPLETED);
        verify(rules, times(2)).save(rule);
    }

    private void idsReserved(int count) {
        when(cashFlowApi.appendExpected(any(), anyString(), any())).thenReturn(reservedIds(count));
    }

    private static List<CashChangeId> reservedIds(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> CashChangeId.of("CC%010d".formatted(1_000_000_000L + i)))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<ExpectedCashChange> appendedChanges() {
        ArgumentCaptor<List<ExpectedCashChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(cashFlowApi).appendExpected(any(), anyString(), captor.capture());
        return captor.getValue();
    }

    private static RecurringRule monthlyRule(Money baseAmount, Integer maxOccurrences) {
        RecurrencePattern monthlyOnThe15th = new MonthlyPattern(15, 1, false);
        return RecurringRule.create(
                RecurringRuleId.generate(1),
                UserId.of("U10000001"),
                CASH_FLOW_ID,
                "Rent",
                "monthly rent",
                baseAmount,
                new CategoryName("Housing"),
                monthlyOnThe15th,
                LocalDate.of(2022, 1, 1),
                null,
                maxOccurrences,
                null,
                null,
                FIXED_CLOCK);
    }
}
