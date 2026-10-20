package com.multi.vidulum.recurring_rules.app.commands.resume;

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
 * Resuming is the mirror of pausing, and the handler's own decisions are the same two: what state it
 * accepts, and in which order it writes.
 */
class ResumeRuleCommandHandlerTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final RecurringRuleId RULE_ID = RecurringRuleId.generate(1);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final RecurringRuleExecutor executor = mock(RecurringRuleExecutor.class);

    private final ResumeRuleCommandHandler handler =
            new ResumeRuleCommandHandler(rules, executor, FIXED_CLOCK);

    @Test
    void aRuleThatDoesNotExistIsNotResumed() {
        when(rules.findById(RULE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new ResumeRuleCommand(RULE_ID.id())))
                .isInstanceOf(RuleNotFoundException.class);

        verify(rules, never()).save(any());
        verifyNoInteractions(executor);
    }

    @Test
    void resumingAnActiveRuleRefusesWithoutRegeneratingAnything() {
        storedPausedRule().resume(FIXED_CLOCK);

        assertThatThrownBy(() -> handler.handle(new ResumeRuleCommand(RULE_ID.id())))
                .isInstanceOf(InvalidRuleStateException.class)
                .isNotInstanceOf(IllegalStateException.class);

        verifyNoInteractions(executor);
        verify(rules, never()).save(any());
    }

    @Test
    void theRuleIsActiveAgainBeforeItPredicts() {
        RecurringRule rule = storedPausedRule();

        handler.handle(new ResumeRuleCommand(RULE_ID.id()));

        InOrder order = inOrder(rules, executor);
        order.verify(rules).save(rule);
        order.verify(executor).generate(rule);
        assertThat(rule.getStatus()).isEqualTo(RuleStatus.ACTIVE);
        assertThat(rule.getPauseInfo()).isEmpty();
    }

    /**
     * A properly paused rule has nothing left to withdraw, so this call looks redundant - and is
     * not: pausing writes twice, and a crash between them leaves predictions behind that only this
     * {@code clear} removes. One call to make resuming idempotent in that state.
     */
    @Test
    void theLeftoversOfAHalfFinishedPauseAreClearedBeforeRegenerating() {
        RecurringRule rule = storedPausedRule();

        handler.handle(new ResumeRuleCommand(RULE_ID.id()));

        InOrder order = inOrder(executor);
        order.verify(executor).clear(rule);
        order.verify(executor).generate(rule);
    }

    private RecurringRule storedPausedRule() {
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
        rule.pause(PauseInfo.indefinite(FIXED_CLOCK.instant(), "holiday"), FIXED_CLOCK);
        when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule));
        return rule;
    }
}
