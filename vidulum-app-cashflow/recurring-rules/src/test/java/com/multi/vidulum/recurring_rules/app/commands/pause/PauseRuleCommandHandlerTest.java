package com.multi.vidulum.recurring_rules.app.commands.pause;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.RecurringRuleExecutor;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.MonthlyPattern;
import com.multi.vidulum.recurring_rules.domain.PauseInfo;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RuleStatus;
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRuleStateException;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pausing is two writes - the withdrawal of the predictions and the rule's own state - and the order
 * between them is the only thing this handler decides.
 */
class PauseRuleCommandHandlerTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final RecurringRuleId RULE_ID = RecurringRuleId.generate(1);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final RecurringRuleExecutor executor = mock(RecurringRuleExecutor.class);

    private final PauseRuleCommandHandler handler =
            new PauseRuleCommandHandler(rules, executor, FIXED_CLOCK);

    @Test
    void aRuleThatDoesNotExistIsNotPaused() {
        when(rules.findById(RULE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new PauseRuleCommand(RULE_ID.id(), null, "holiday")))
                .isInstanceOf(RuleNotFoundException.class);

        verify(rules, never()).save(any());
        verifyNoInteractions(executor);
    }

    @Test
    void thePredictionsAreWithdrawnBeforeTheRuleStopsPredicting() {
        RecurringRule rule = storedActiveRule();

        handler.handle(new PauseRuleCommand(RULE_ID.id(), null, "holiday"));

        InOrder order = inOrder(executor, rules);
        order.verify(executor).clear(rule);
        order.verify(rules).save(rule);
        assertThat(rule.getStatus())
                .as("a paused rule that kept its future cash changes would still shape the forecast")
                .isEqualTo(RuleStatus.PAUSED);
    }

    @Test
    void aPauseWithoutADateIsIndefiniteAndOneWithADateCarriesIt() {
        RecurringRule indefinite = storedActiveRule();
        handler.handle(new PauseRuleCommand(RULE_ID.id(), null, "holiday"));

        RecurringRule dated = storedActiveRule();
        LocalDate resumeOn = LocalDate.of(2022, 3, 1);
        handler.handle(new PauseRuleCommand(RULE_ID.id(), resumeOn, "parental leave"));

        assertThat(indefinite.getPauseInfo()).contains(
                PauseInfo.indefinite(Instant.parse("2022-01-01T00:00:00Z"), "holiday"));
        assertThat(dated.getPauseInfo()).contains(
                PauseInfo.untilDate(Instant.parse("2022-01-01T00:00:00Z"), resumeOn, "parental leave"));
    }

    /**
     * The guard is here and not only in the aggregate because the aggregate answers with an
     * {@link IllegalStateException}, which has no {@code ErrorCode} - so the endpoint would answer
     * 500 for what is a perfectly ordinary mistake.
     */
    @Test
    void pausingAPausedRuleRefusesWithoutTouchingItsCashChanges() {
        RecurringRule rule = storedActiveRule();
        rule.pause(PauseInfo.indefinite(FIXED_CLOCK.instant(), "already"), FIXED_CLOCK);

        assertThatThrownBy(() -> handler.handle(new PauseRuleCommand(RULE_ID.id(), null, "again")))
                .isInstanceOf(InvalidRuleStateException.class)
                .isNotInstanceOf(IllegalStateException.class);

        verifyNoInteractions(executor);
        verify(rules, never()).save(any());
    }

    private RecurringRule storedActiveRule() {
        RecurringRule rule = RecurringRule.create(
                RULE_ID,
                UserId.of("U10000001"),
                CASH_FLOW_ID,
                "Rent",
                "monthly rent",
                Money.of(-1500, "PLN"),
                new CategoryName("Housing"),
                new MonthlyPattern(15, 1, false),
                LocalDate.of(2022, 1, 1),
                null,
                null,
                null,
                null,
                FIXED_CLOCK);
        when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule));
        return rule;
    }
}
