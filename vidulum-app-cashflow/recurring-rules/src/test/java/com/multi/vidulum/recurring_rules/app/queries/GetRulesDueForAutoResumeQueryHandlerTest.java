package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.MonthlyPattern;
import com.multi.vidulum.recurring_rules.domain.PauseInfo;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.recurring_rules.domain.RuleStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tonight's list. Which rules are due is the repository's query; what this handler decides is that
 * the scheduler gets <b>snapshots</b>, so it cannot write through what it was handed.
 */
class GetRulesDueForAutoResumeQueryHandlerTest {

    private static final UserId USER_ID = UserId.of("U10000001");
    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final LocalDate TODAY = LocalDate.of(2022, 1, 1);
    private static final Instant NOW = Instant.parse("2022-01-01T03:00:00Z");

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final GetRulesDueForAutoResumeQueryHandler handler =
            new GetRulesDueForAutoResumeQueryHandler(rules);

    @Test
    void everyDueRuleComesBackAsASnapshotCarryingWhatTheSchedulerLogs() {
        when(rules.findPausedRulesWithResumeDateOnOrBefore(TODAY))
                .thenReturn(List.of(pausedUntil(1, LocalDate.of(2021, 12, 28))));

        List<RecurringRuleSnapshot> due = handler.query(new GetRulesDueForAutoResumeQuery(TODAY));

        assertThat(due).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo(RecurringRuleId.generate(1));
            assertThat(rule.name()).isEqualTo("Rent");
            assertThat(rule.status()).isEqualTo(RuleStatus.PAUSED);
            assertThat(rule.pauseInfo().getResumeDate()).contains(LocalDate.of(2021, 12, 28));
        });
    }

    @Test
    void theQueryIsAskedAboutTheDateItCarriesNotAboutToday() {
        LocalDate missedNight = LocalDate.of(2021, 12, 31);
        when(rules.findPausedRulesWithResumeDateOnOrBefore(missedNight)).thenReturn(List.of());

        assertThat(handler.query(new GetRulesDueForAutoResumeQuery(missedNight)))
                .as("a catch-up run asks about the night it is working on")
                .isEmpty();
    }

    private static RecurringRule pausedUntil(long sequence, LocalDate resumeDate) {
        RecurringRule rule = RecurringRule.create(
                RecurringRuleId.generate(sequence),
                USER_ID,
                CASH_FLOW_ID,
                "Rent",
                "monthly rent",
                Money.of(-1500, "PLN"),
                new CategoryName("Housing"),
                new MonthlyPattern(15, 1, false),
                LocalDate.of(2022, 1, 1),
                null, null, null, null,
                NOW);
        rule.pause(PauseInfo.untilDate(NOW, resumeDate, "holiday"), NOW);
        return rule;
    }
}
