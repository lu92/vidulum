package com.multi.vidulum.recurring_rules.app.commands.create;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.app.CashFlowCategoryValidator;
import com.multi.vidulum.recurring_rules.app.RecurringRuleExecutor;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.OncePattern;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.exceptions.CategoryNotFoundException;
import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDateRangeException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the handler itself is responsible for: the order of the checks, and writing nothing when one
 * of them refuses.
 *
 * <p>The validator is a mock here - whether a category exists is
 * {@code CashFlowCategoryValidatorTest}'s question, and what matters at this level is that a
 * refusal stops the rule from being stored and stops any cash change from being generated.
 */
class CreateRuleCommandHandlerTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final LocalDate FAR_FUTURE = LocalDate.of(2030, 6, 15);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final CashFlowCategoryValidator categoryValidator = mock(CashFlowCategoryValidator.class);
    private final RecurringRuleExecutor executor = mock(RecurringRuleExecutor.class);

    private final CreateRuleCommandHandler handler = new CreateRuleCommandHandler(
            rules,
            categoryValidator,
            executor,
            Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void aRefusedCategoryLeavesNothingBehind() {
        doThrow(new CategoryNotFoundException(CASH_FLOW_ID, new CategoryName("Bonus")))
                .when(categoryValidator).validate(any(), any(), eq(true));

        assertThatThrownBy(() -> handler.handle(command("Bonus", 100.0)))
                .isInstanceOf(CategoryNotFoundException.class);

        verify(rules, never()).save(any());
        verifyNoInteractions(executor);
    }

    @Test
    void theCategoryIsCheckedBeforeTheDates() {
        doThrow(new CategoryNotFoundException(CASH_FLOW_ID, new CategoryName("Bonus")))
                .when(categoryValidator).validate(any(), any(), eq(true));

        CreateRuleCommand backwards = new CreateRuleCommand(
                "U10000001", CASH_FLOW_ID.id(), "Rule", "description",
                Money.of(100.0, "PLN"), new CategoryName("Bonus"),
                new OncePattern(FAR_FUTURE),
                FAR_FUTURE, FAR_FUTURE.minusDays(1),
                null, null, null);

        assertThatThrownBy(() -> handler.handle(backwards))
                .as("both the category and the date range are wrong; the category decides, which is "
                        + "the order the method had before it moved out of RecurringRuleService")
                .isInstanceOf(CategoryNotFoundException.class)
                .isNotInstanceOf(InvalidDateRangeException.class);
    }

    @Test
    void anAcceptedRuleIsStoredAndItsCashChangesGenerated() {
        when(rules.generateNextSequence()).thenReturn(1L);

        RecurringRuleId ruleId = handler.handle(command("Salary", 100.0));

        assertThat(ruleId).isNotNull();
        verify(categoryValidator).validate(CASH_FLOW_ID, new CategoryName("Salary"), true);
        verify(rules).save(any(RecurringRule.class));
        verify(executor).generate(any(RecurringRule.class));
    }

    @Test
    void aNegativeAmountIsCheckedAgainstTheOutflowSide() {
        when(rules.generateNextSequence()).thenReturn(1L);

        handler.handle(command("Rent", -100.0));

        verify(categoryValidator).validate(CASH_FLOW_ID, new CategoryName("Rent"), false);
    }

    private static CreateRuleCommand command(String categoryName, double amount) {
        return new CreateRuleCommand(
                "U10000001",
                CASH_FLOW_ID.id(),
                "Rule",
                "description",
                Money.of(amount, "PLN"),
                new CategoryName(categoryName),
                new OncePattern(FAR_FUTURE),
                FAR_FUTURE,
                null,
                null,
                null,
                null);
    }
}
