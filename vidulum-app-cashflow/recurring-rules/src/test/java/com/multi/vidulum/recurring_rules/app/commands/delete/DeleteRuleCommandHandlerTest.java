package com.multi.vidulum.recurring_rules.app.commands.delete;

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
 * Deleting a rule retires it and withdraws what it predicted - and nothing more than that. What the
 * owner has already confirmed is a fact about their money, and cashflow keeps it.
 */
class DeleteRuleCommandHandlerTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final RecurringRuleId RULE_ID = RecurringRuleId.generate(1);
    private static final Instant NOW = Instant.parse("2022-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final RecurringRuleExecutor executor = mock(RecurringRuleExecutor.class);

    private final DeleteRuleCommandHandler handler =
            new DeleteRuleCommandHandler(rules, executor, FIXED_CLOCK);

    @Test
    void aRuleThatDoesNotExistIsNotDeleted() {
        when(rules.findById(RULE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new DeleteRuleCommand(RULE_ID.id(), "no longer needed")))
                .isInstanceOf(RuleNotFoundException.class);

        verify(rules, never()).save(any());
        verifyNoInteractions(executor);
    }

    @Test
    void thePredictionsAreWithdrawnBeforeTheRuleIsRetired() {
        RecurringRule rule = storedRule();

        handler.handle(new DeleteRuleCommand(RULE_ID.id(), "no longer needed"));

        InOrder order = inOrder(executor, rules);
        order.verify(executor).clear(rule);
        order.verify(rules).save(rule);
        assertThat(rule.getStatus()).isEqualTo(RuleStatus.DELETED);
    }

    @Test
    void aPausedRuleCanStillBeDeleted() {
        RecurringRule rule = storedRule();
        rule.pause(PauseInfo.indefinite(NOW, "holiday"), NOW);

        handler.handle(new DeleteRuleCommand(RULE_ID.id(), "no longer needed"));

        assertThat(rule.getStatus())
                .as("only DELETED is refused - pausing is not a reason to keep a rule around")
                .isEqualTo(RuleStatus.DELETED);
        verify(executor).clear(rule);
    }

    @Test
    void deletingADeletedRuleRefusesWithoutTouchingItsCashChanges() {
        RecurringRule rule = storedRule();
        rule.delete("gone", NOW);

        assertThatThrownBy(() -> handler.handle(new DeleteRuleCommand(RULE_ID.id(), "again")))
                .isInstanceOf(InvalidRuleStateException.class)
                .isNotInstanceOf(IllegalStateException.class);

        verifyNoInteractions(executor);
        verify(rules, never()).save(any());
    }

    private RecurringRule storedRule() {
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
                NOW);
        when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule));
        return rule;
    }
}
