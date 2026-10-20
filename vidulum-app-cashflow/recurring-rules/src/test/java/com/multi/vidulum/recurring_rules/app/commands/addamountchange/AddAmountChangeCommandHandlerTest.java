package com.multi.vidulum.recurring_rules.app.commands.addamountchange;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.RecurringRuleExecutor;
import com.multi.vidulum.recurring_rules.domain.AmountChange;
import com.multi.vidulum.recurring_rules.domain.AmountChangeId;
import com.multi.vidulum.recurring_rules.domain.AmountChangeType;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.MonthlyPattern;
import com.multi.vidulum.recurring_rules.domain.PauseInfo;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRuleStateException;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Recording a raise, a cut or a one-off difference - and the rule that decides whether the forecast
 * is rebuilt for it.
 */
class AddAmountChangeCommandHandlerTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final RecurringRuleId RULE_ID = RecurringRuleId.generate(1);
    private static final Instant NOW = Instant.parse("2022-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final RecurringRuleExecutor executor = mock(RecurringRuleExecutor.class);

    private final AddAmountChangeCommandHandler handler =
            new AddAmountChangeCommandHandler(rules, executor, FIXED_CLOCK);

    @Test
    void aRuleThatDoesNotExistRecordsNothing() {
        when(rules.findById(RULE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(command(Money.of(500, "PLN"))))
                .isInstanceOf(RuleNotFoundException.class);

        verify(rules, never()).save(any());
        verifyNoInteractions(executor);
    }

    @Test
    void theChangeIsRecordedAndTheForecastRebuiltAroundIt() {
        when(rules.generateNextAmountChangeSequence()).thenReturn(7L);
        RecurringRule rule = storedActiveRule();

        AmountChangeId changeId = handler.handle(command(Money.of(500, "PLN")));

        assertThat(changeId).isEqualTo(AmountChangeId.generate(7L));
        assertThat(rule.getAmountChanges()).containsExactly(
                new AmountChange(changeId, Money.of(500, "PLN"), AmountChangeType.PERMANENT, "Raise"));
        verify(rules).save(rule);
        verify(executor).clear(rule);
        verify(executor).generate(rule);
    }

    /**
     * A paused rule holds no predictions, so there is nothing to rebuild - and rebuilding would
     * contradict the pause, leaving cash changes behind that only a resume could withdraw. Its new
     * amount takes effect when it resumes, which regenerates from today anyway.
     */
    @Test
    void aPausedRuleRecordsTheChangeWithoutRegenerating() {
        when(rules.generateNextAmountChangeSequence()).thenReturn(7L);
        RecurringRule rule = storedActiveRule();
        rule.pause(PauseInfo.indefinite(NOW, "holiday"), NOW);

        handler.handle(command(Money.of(500, "PLN")));

        assertThat(rule.getAmountChanges()).hasSize(1);
        verify(rules).save(rule);
        verifyNoInteractions(executor);
    }

    @Test
    void aDeletedRuleTakesNoMoreAmountChanges() {
        RecurringRule rule = storedActiveRule();
        rule.delete("gone", NOW);

        assertThatThrownBy(() -> handler.handle(command(Money.of(500, "PLN"))))
                .isInstanceOf(InvalidRuleStateException.class)
                .isNotInstanceOf(IllegalStateException.class);

        assertThat(rule.getAmountChanges()).isEmpty();
        verify(rules, never()).save(any());
        verifyNoInteractions(executor);
    }

    private static AddAmountChangeCommand command(Money amount) {
        return new AddAmountChangeCommand(RULE_ID.id(), amount, AmountChangeType.PERMANENT, "Raise");
    }

    private RecurringRule storedActiveRule() {
        RecurringRule rule = RecurringRule.create(
                RULE_ID,
                UserId.of("U10000001"),
                CASH_FLOW_ID,
                "Salary",
                "monthly salary",
                Money.of(5000, "PLN"),
                new CategoryName("Income"),
                new MonthlyPattern(15, 1, false),
                LocalDate.of(2022, 1, 1),
                null, null, null, null,
                NOW);
        when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule));
        return rule;
    }
}
