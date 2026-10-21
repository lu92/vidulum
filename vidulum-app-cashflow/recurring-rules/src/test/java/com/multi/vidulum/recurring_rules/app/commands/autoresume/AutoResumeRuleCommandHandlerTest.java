package com.multi.vidulum.recurring_rules.app.commands.autoresume;

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
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The scheduler's path through a resume, which had no test and a defect: it set the rule ACTIVE and
 * generated nothing, because the writes needed a bearer token it did not have. The predictions were
 * missing until somebody edited the rule by hand.
 *
 * <p>It differs from {@code ResumeRuleCommandHandler} on purpose in one respect, and that difference
 * is why this is a command of its own rather than a flag on that one: a rule the scheduler can no
 * longer resume is skipped with a warning, because nobody is waiting for an answer.
 */
class AutoResumeRuleCommandHandlerTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final RecurringRuleId RULE_ID = RecurringRuleId.generate(1);
    private static final LocalDate RESUME_DATE = LocalDate.of(2022, 1, 1);
    private static final Instant NOW = Instant.parse("2022-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final RecurringRuleExecutor executor = mock(RecurringRuleExecutor.class);

    private final AutoResumeRuleCommandHandler handler =
            new AutoResumeRuleCommandHandler(rules, executor, FIXED_CLOCK);

    @Test
    void anAutoResumedRulePredictsAgain() {
        RecurringRule rule = storedPausedRule();

        handler.handle(new AutoResumeRuleCommand(RULE_ID.id(), RESUME_DATE));

        InOrder order = inOrder(rules, executor);
        order.verify(rules).save(rule);
        order.verify(executor).generate(rule);
        assertThat(rule.getStatus()).isEqualTo(RuleStatus.ACTIVE);
    }

    @Test
    void aRuleResumedByHandInTheMeantimeIsSkipped() {
        storedPausedRule().resume(NOW);

        assertThatCode(() -> handler.handle(new AutoResumeRuleCommand(RULE_ID.id(), RESUME_DATE)))
                .as("the scheduler reads a list and acts on it later; losing a race is not an error")
                .doesNotThrowAnyException();

        verify(rules, never()).save(any());
        verifyNoInteractions(executor);
    }

    @Test
    void aRuleDeletedInTheMeantimeIsSkipped() {
        when(rules.findById(RULE_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> handler.handle(new AutoResumeRuleCommand(RULE_ID.id(), RESUME_DATE)))
                .doesNotThrowAnyException();

        verify(rules, never()).save(any());
        verifyNoInteractions(executor);
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
                NOW);
        rule.pause(PauseInfo.untilDate(NOW, RESUME_DATE, "holiday"), NOW);
        when(rules.findById(RULE_ID)).thenReturn(Optional.of(rule));
        return rule;
    }
}
