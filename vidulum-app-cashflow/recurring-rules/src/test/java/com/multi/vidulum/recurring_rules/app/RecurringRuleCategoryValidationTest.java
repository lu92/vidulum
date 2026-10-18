package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.CategoryNodeView;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.commands.CreateRuleCommand;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.OncePattern;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.exceptions.CategoryNotFoundException;
import com.multi.vidulum.recurring_rules.infrastructure.CashFlowHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Creating a rule refuses a category the cash flow does not have - and this is the module's own
 * decision, not cashflow's.
 *
 * <p>It had no test. The only one that touched the logic behind it drove
 * {@code CashFlowHttpClient.validateCategoryExists}, which read the whole cash flow out of a
 * {@code Map<String, Object>} and had no production caller; it went with that method. The service
 * now asks {@link CashFlowSkeletonView}, so the refusal is asserted here, where it is made.
 *
 * <p>Every rule below starts in 2030, outside the twelve-month forecast window from the fixed
 * clock, so no occurrence is generated and the write path stays out of the way. What is under test
 * is the validation, not the generation.
 */
class RecurringRuleCategoryValidationTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2030, 6, 15);

    private final DomainRecurringRuleRepository rules = mock(DomainRecurringRuleRepository.class);
    private final CashFlowModuleApi cashFlowApi = mock(CashFlowModuleApi.class);
    private final CashFlowHttpClient cashFlowClient = mock(CashFlowHttpClient.class);

    private final RecurringRuleService service =
            new RecurringRuleService(rules, cashFlowApi, cashFlowClient, FIXED_CLOCK);

    @Test
    void aCategoryTheCashFlowDoesNotHaveIsRefusedAndNothingIsWritten() {
        givenSkeleton(List.of(node("Salary")), List.of(node("Home")));

        assertThatThrownBy(() -> service.handle(rule("Bonus", 100.0), "token"))
                .isInstanceOf(CategoryNotFoundException.class)
                .hasMessageContaining("Bonus")
                .hasMessageContaining(CASH_FLOW_ID.id());

        verify(rules, never()).save(any());
        verifyNoInteractions(cashFlowClient);
    }

    /**
     * The direction is part of the question. A cash flow may use one word on both sides, and
     * "Rent" as an outflow is not the same category as "Rent" as an inflow - which is why the
     * service asks the per-side sets rather than {@code allCategoryNames()}.
     */
    @Test
    void aCategoryThatExistsOnlyOnTheOtherSideIsRefused() {
        givenSkeleton(List.of(node("Salary")), List.of(node("Rent")));

        assertThatThrownBy(() -> service.handle(rule("Rent", 100.0), "token"))
                .as("a positive amount is an inflow, and Rent is an outflow category here")
                .isInstanceOf(CategoryNotFoundException.class);

        assertThatThrownBy(() -> service.handle(rule("Salary", -100.0), "token"))
                .as("and the same the other way round")
                .isInstanceOf(CategoryNotFoundException.class);
    }

    /**
     * The name sets flatten, so a subcategory counts. This is what the deleted client test called
     * "nested categories", reached through the contract instead of a hand-rolled recursive walk.
     */
    @Test
    void aSubcategoryIsAcceptedJustLikeATopLevelOne() {
        givenSkeleton(List.of(node("Salary", "Bonus")), List.of(node("Home")));
        when(rules.generateNextSequence()).thenReturn(1L);

        service.handle(rule("Bonus", 100.0), "token");

        verify(rules).save(any(RecurringRule.class));
        verifyNoInteractions(cashFlowClient);
    }

    @Test
    void aCashFlowThatDoesNotExistIsRefusedByTheContract() {
        when(cashFlowApi.skeletonOf(CASH_FLOW_ID))
                .thenThrow(new CashFlowNotFoundException(CASH_FLOW_ID));

        assertThatThrownBy(() -> service.handle(rule("Salary", 100.0), "token"))
                .as("it carries an ErrorCode and answers 404 on its own, so it must not be "
                        + "translated into this module's communication failure")
                .isInstanceOf(CashFlowNotFoundException.class);

        verify(rules, never()).save(any());
    }

    @Test
    void theContractIsAskedOncePerRule() {
        givenSkeleton(List.of(node("Salary")), List.of(node("Home")));
        when(rules.generateNextSequence()).thenReturn(1L);

        service.handle(rule("Salary", 100.0), "token");

        verify(cashFlowApi).skeletonOf(CASH_FLOW_ID);
        verifyNoMoreInteractions(cashFlowApi);
    }

    private void givenSkeleton(List<CategoryNodeView> inflow, List<CategoryNodeView> outflow) {
        when(cashFlowApi.skeletonOf(CASH_FLOW_ID)).thenReturn(new CashFlowSkeletonView(
                CASH_FLOW_ID,
                UserId.of("U10000001"),
                CashFlowSkeletonView.LifecyclePhase.OPEN,
                YearMonth.of(2022, 1),
                YearMonth.of(2022, 1),
                inflow,
                outflow));
    }

    private static CategoryNodeView node(String name, String... subCategories) {
        CategoryName parent = new CategoryName(name);
        List<CategoryNodeView> children = java.util.Arrays.stream(subCategories)
                .map(sub -> new CategoryNodeView(new CategoryName(sub), parent, false, List.of()))
                .toList();
        return new CategoryNodeView(parent, null, false, children);
    }

    private static CreateRuleCommand rule(String categoryName, double amount) {
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
