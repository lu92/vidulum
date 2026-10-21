package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.commands.autoresume.AutoResumeRuleCommand;
import com.multi.vidulum.recurring_rules.app.queries.GetRulesDueForAutoResumeQuery;
import com.multi.vidulum.recurring_rules.domain.MonthlyPattern;
import com.multi.vidulum.recurring_rules.domain.PauseInfo;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The nightly job, which had no test of its own - and its own job is the loop: asking once, telling
 * once per rule, and surviving a rule that cannot be told.
 */
class RecurringRuleAutoResumeSchedulerTest {

    private static final UserId USER_ID = UserId.of("U10000001");
    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final LocalDate TODAY = LocalDate.of(2022, 1, 1);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-01-01T03:00:00Z"), ZoneOffset.UTC);

    private final CommandGateway commandGateway = mock(CommandGateway.class);
    private final QueryGateway queryGateway = mock(QueryGateway.class);

    private final RecurringRuleAutoResumeScheduler scheduler =
            new RecurringRuleAutoResumeScheduler(commandGateway, queryGateway, FIXED_CLOCK);

    @Test
    void itAsksForTonightsListAndSendsOneCommandPerRule() {
        due(pausedUntil(1, LocalDate.of(2021, 12, 28)), pausedUntil(2, TODAY));

        scheduler.autoResumePausedRules();

        verify(queryGateway).send(new GetRulesDueForAutoResumeQuery(TODAY));
        verify(commandGateway).send(new AutoResumeRuleCommand(
                RecurringRuleId.generate(1).id(), LocalDate.of(2021, 12, 28)));
        verify(commandGateway).send(new AutoResumeRuleCommand(
                RecurringRuleId.generate(2).id(), TODAY));
    }

    /**
     * The resume date is quoted from the rule, not from the clock: a rule paused until 28 December
     * resumed on 1 January woke up because of that date, and the log has to say so.
     */
    @Test
    void theDateTheRuleWasPausedUntilTravelsWithTheCommand() {
        due(pausedUntil(1, LocalDate.of(2021, 12, 28)));

        scheduler.autoResumePausedRules();

        verify(commandGateway).send(new AutoResumeRuleCommand(
                RecurringRuleId.generate(1).id(), LocalDate.of(2021, 12, 28)));
    }

    /**
     * The reason this is a loop of commands rather than one command for the batch.
     */
    @Test
    void oneRuleThatCannotBeResumedDoesNotEndTheNightsWork() {
        due(pausedUntil(1, TODAY), pausedUntil(2, TODAY), pausedUntil(3, TODAY));
        when(commandGateway.send(new AutoResumeRuleCommand(RecurringRuleId.generate(2).id(), TODAY)))
                .thenThrow(new IllegalStateException("something unexpected"));

        assertThatCode(scheduler::autoResumePausedRules).doesNotThrowAnyException();

        verify(commandGateway).send(new AutoResumeRuleCommand(RecurringRuleId.generate(3).id(), TODAY));
    }

    /**
     * The second half of F57. With a version on the rule, a person editing it at 03:00 makes the
     * job's write fail - and dropping the rule there would mean it stays paused for another day.
     * The command is sent again, and because its handler re-reads the rule, the retry works from
     * the winner's state.
     */
    @Test
    void aConflictIsRetriedRatherThanCostingTheRuleItsNight() {
        due(pausedUntil(1, TODAY));
        AutoResumeRuleCommand expected = new AutoResumeRuleCommand(RecurringRuleId.generate(1).id(), TODAY);
        when(commandGateway.send(expected))
                .thenThrow(new OptimisticLockingFailureException("owner wrote first"))
                .thenReturn(null);

        scheduler.autoResumePausedRules();

        verify(commandGateway, times(2)).send(expected);
    }

    /**
     * And a conflict that will not clear is still only this rule's problem.
     */
    @Test
    void aConflictThatNeverClearsLeavesTheRestOfTheListAlone() {
        due(pausedUntil(1, TODAY), pausedUntil(2, TODAY));
        when(commandGateway.send(new AutoResumeRuleCommand(RecurringRuleId.generate(1).id(), TODAY)))
                .thenThrow(new OptimisticLockingFailureException("owner keeps writing"));

        assertThatCode(scheduler::autoResumePausedRules).doesNotThrowAnyException();

        verify(commandGateway).send(new AutoResumeRuleCommand(RecurringRuleId.generate(2).id(), TODAY));
    }

    @Test
    void aQuietNightTellsNobodyAnything() {
        when(queryGateway.send(new GetRulesDueForAutoResumeQuery(TODAY))).thenReturn(List.of());

        scheduler.autoResumePausedRules();

        verifyNoInteractions(commandGateway);
    }

    @Test
    void theManualTriggerDoesExactlyWhatTheNightlyRunDoes() {
        due(pausedUntil(1, TODAY));

        scheduler.triggerManualAutoResume();

        verify(queryGateway).send(new GetRulesDueForAutoResumeQuery(TODAY));
        verify(commandGateway).send(new AutoResumeRuleCommand(RecurringRuleId.generate(1).id(), TODAY));
    }

    @Test
    void aRuleWithNoResumeDateIsLoggedAgainstTodayRatherThanSkipped() {
        RecurringRule indefinite = rule(1);
        indefinite.pause(PauseInfo.indefinite(FIXED_CLOCK.instant(), "holiday"), FIXED_CLOCK.instant());
        when(queryGateway.send(new GetRulesDueForAutoResumeQuery(TODAY)))
                .thenReturn(List.of(indefinite.getSnapshot()));

        scheduler.autoResumePausedRules();

        verify(commandGateway).send(new AutoResumeRuleCommand(RecurringRuleId.generate(1).id(), TODAY));
    }

    private void due(RecurringRuleSnapshot... snapshots) {
        when(queryGateway.send(new GetRulesDueForAutoResumeQuery(TODAY))).thenReturn(List.of(snapshots));
    }

    private static RecurringRuleSnapshot pausedUntil(long sequence, LocalDate resumeDate) {
        RecurringRule rule = rule(sequence);
        rule.pause(PauseInfo.untilDate(FIXED_CLOCK.instant(), resumeDate, "holiday"), FIXED_CLOCK.instant());
        return rule.getSnapshot();
    }

    private static RecurringRule rule(long sequence) {
        return RecurringRule.create(
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
                FIXED_CLOCK.instant());
    }
}
