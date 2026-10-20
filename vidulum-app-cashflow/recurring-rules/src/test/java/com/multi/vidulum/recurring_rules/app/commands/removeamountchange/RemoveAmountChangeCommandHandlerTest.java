package com.multi.vidulum.recurring_rules.app.commands.removeamountchange;

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
import com.multi.vidulum.recurring_rules.domain.exceptions.AmountChangeNotFoundException;
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
 * Taking an amount change back. Mirror of the add side, including which states accept it and when
 * the forecast is rebuilt.
 */
class RemoveAmountChangeCommandHandlerTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final RecurringRuleId RULE_ID = RecurringRuleId.generate(1);
    private static final AmountChangeId CHANGE_ID = AmountChangeId.generate(7L);
    private static final Instant NOW = Instant.parse("2022-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final RecurringRuleExecutor executor = mock(RecurringRuleExecutor.class);

    private final RemoveAmountChangeCommandHandler handler =
            new RemoveAmountChangeCommandHandler(rules, executor, FIXED_CLOCK);

    @Test
    void aRuleThatDoesNotExistRemovesNothing() {
        when(rules.findById(RULE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new RemoveAmountChangeCommand(RULE_ID.id(), CHANGE_ID.id())))
                .isInstanceOf(RuleNotFoundException.class);

        verify(rules, never()).save(any());
        verifyNoInteractions(executor);
    }

    @Test
    void theChangeGoesAndTheForecastIsRebuiltWithoutIt() {
        RecurringRule rule = storedActiveRuleWithTheChange();

        handler.handle(new RemoveAmountChangeCommand(RULE_ID.id(), CHANGE_ID.id()));

        assertThat(rule.getAmountChanges()).isEmpty();
        verify(rules).save(rule);
        verify(executor).clear(rule);
        verify(executor).generate(rule);
    }

    /**
     * A missing amount change is the caller's mistake, not something to pass over: the id came from
     * somewhere, so its absence is news.
     */
    @Test
    void anAmountChangeThatIsNotThereIsRefused() {
        RecurringRule rule = storedActiveRuleWithTheChange();

        assertThatThrownBy(() -> handler.handle(
                new RemoveAmountChangeCommand(RULE_ID.id(), AmountChangeId.generate(99L).id())))
                .isInstanceOf(AmountChangeNotFoundException.class);

        assertThat(rule.getAmountChanges())
                .as("the refusal must not take the existing change with it")
                .hasSize(1);
        verify(rules, never()).save(any());
        verifyNoInteractions(executor);
    }

    @Test
    void aPausedRuleRemovesTheChangeWithoutRegenerating() {
        RecurringRule rule = storedActiveRuleWithTheChange();
        rule.pause(PauseInfo.indefinite(NOW, "holiday"), NOW);

        handler.handle(new RemoveAmountChangeCommand(RULE_ID.id(), CHANGE_ID.id()));

        assertThat(rule.getAmountChanges()).isEmpty();
        verify(rules).save(rule);
        verifyNoInteractions(executor);
    }

    @Test
    void aDeletedRuleIsLeftAlone() {
        RecurringRule rule = storedActiveRuleWithTheChange();
        rule.delete("gone", NOW);

        assertThatThrownBy(() -> handler.handle(new RemoveAmountChangeCommand(RULE_ID.id(), CHANGE_ID.id())))
                .isInstanceOf(InvalidRuleStateException.class)
                .isNotInstanceOf(IllegalStateException.class);

        assertThat(rule.getAmountChanges()).hasSize(1);
        verify(rules, never()).save(any());
    }

    private RecurringRule storedActiveRuleWithTheChange() {
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
        rule.addAmountChange(
                new AmountChange(CHANGE_ID, Money.of(500, "PLN"), AmountChangeType.PERMANENT, "Raise"), NOW);
        when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule));
        return rule;
    }
}
