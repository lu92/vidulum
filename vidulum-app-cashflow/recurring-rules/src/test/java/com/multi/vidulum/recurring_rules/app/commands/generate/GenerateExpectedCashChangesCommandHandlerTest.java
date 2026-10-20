package com.multi.vidulum.recurring_rules.app.commands.generate;

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
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRuleStateException;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rebuilding a rule's predictions on request. The handler stores nothing itself - the executor saves
 * after each half - so what is left to decide is which states it accepts and that the withdrawal
 * comes first.
 */
class GenerateExpectedCashChangesCommandHandlerTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final RecurringRuleId RULE_ID = RecurringRuleId.generate(1);
    private static final Instant NOW = Instant.parse("2022-01-01T00:00:00Z");

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final RecurringRuleExecutor executor = mock(RecurringRuleExecutor.class);

    private final GenerateExpectedCashChangesCommandHandler handler =
            new GenerateExpectedCashChangesCommandHandler(rules, executor);

    @Test
    void aRuleThatDoesNotExistGeneratesNothing() {
        when(rules.findById(RULE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new GenerateExpectedCashChangesCommand(RULE_ID.id())))
                .isInstanceOf(RuleNotFoundException.class);

        verifyNoInteractions(executor);
    }

    @Test
    void whatTheRuleHadPredictedIsWithdrawnBeforeItPredictsAgain() {
        RecurringRule rule = storedActiveRule();

        handler.handle(new GenerateExpectedCashChangesCommand(RULE_ID.id()));

        InOrder order = inOrder(executor);
        order.verify(executor).clear(rule);
        order.verify(executor).generate(rule);
    }

    /**
     * Not a formality: generating for a paused rule would put back exactly what pausing withdrew,
     * and the rule would stay PAUSED while shaping the forecast again.
     */
    @Test
    void aPausedRuleIsRefusedRatherThanQuietlyResurrected() {
        RecurringRule rule = storedActiveRule();
        rule.pause(PauseInfo.indefinite(NOW, "holiday"), NOW);

        assertThatThrownBy(() -> handler.handle(new GenerateExpectedCashChangesCommand(RULE_ID.id())))
                .isInstanceOf(InvalidRuleStateException.class)
                .isNotInstanceOf(IllegalStateException.class);

        verifyNoInteractions(executor);
        verify(rules, never()).save(any());
    }

    @Test
    void aDeletedRuleIsRefusedTheSameWay() {
        RecurringRule rule = storedActiveRule();
        rule.delete("gone", NOW);

        assertThatThrownBy(() -> handler.handle(new GenerateExpectedCashChangesCommand(RULE_ID.id())))
                .isInstanceOf(InvalidRuleStateException.class);

        verifyNoInteractions(executor);
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
                null, null, null, null,
                NOW);
        when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule));
        return rule;
    }
}
