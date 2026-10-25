package com.multi.vidulum.recurring_rules.app;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.CategoryNodeView;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.BankAccountId;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.domain.exceptions.CategoryNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A rule may only name a category its cash flow has, on the right side.
 *
 * <p>This had no test anywhere - not as a unit, and not in the 57 integration tests of recurring
 * rules, which assert 404 only for rules themselves. The one test that touched the logic behind it
 * drove {@code validateCategoryExists} on the module's own HTTP client - a method with no
 * production caller, on a client that no longer exists - which
 * read the whole cash flow out of a {@code Map<String, Object>}; it went with that method. So when
 * the check moved onto the contract there was nothing to prove it had been preserved, which is why
 * these cases exist.
 *
 * <p>A real {@link CashFlowCategoryValidator} over a stubbed contract, deliberately: the questions
 * below - does flattening reach subcategories, does the direction matter - are answered by the
 * skeleton, so mocking the validator would test nothing.
 */
class CashFlowCategoryValidatorTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");

    private final CashFlowModuleApi cashFlowApi = mock(CashFlowModuleApi.class);
    private final CashFlowCategoryValidator validator = new CashFlowCategoryValidator(cashFlowApi);

    @Test
    void aCategoryTheCashFlowDoesNotHaveIsRefused() {
        givenSkeleton(List.of(node("Salary")), List.of(node("Home")));

        assertThatThrownBy(() -> validator.validate(CASH_FLOW_ID, new CategoryName("Bonus"), true))
                .isInstanceOf(CategoryNotFoundException.class)
                .hasMessageContaining("Bonus")
                .hasMessageContaining(CASH_FLOW_ID.id());
    }

    /**
     * The direction is part of the question. A cash flow may use one word on both sides, and
     * "Rent" as an outflow is not the category "Rent" as an inflow - which is why this asks the
     * per-side sets rather than {@code allCategoryNames()}.
     */
    @Test
    void aCategoryThatExistsOnlyOnTheOtherSideIsRefused() {
        givenSkeleton(List.of(node("Salary")), List.of(node("Rent")));

        assertThatThrownBy(() -> validator.validate(CASH_FLOW_ID, new CategoryName("Rent"), true))
                .isInstanceOf(CategoryNotFoundException.class);
        assertThatThrownBy(() -> validator.validate(CASH_FLOW_ID, new CategoryName("Salary"), false))
                .as("and the same the other way round")
                .isInstanceOf(CategoryNotFoundException.class);
    }

    /**
     * What the deleted client test called "nested categories", reached through the contract instead
     * of a hand-rolled recursive walk over a JSON map.
     */
    @Test
    void aSubcategoryCountsJustLikeATopLevelOne() {
        givenSkeleton(List.of(node("Salary", "Bonus")), List.of(node("Home")));

        assertThatCode(() -> validator.validate(CASH_FLOW_ID, new CategoryName("Bonus"), true))
                .doesNotThrowAnyException();
    }

    @Test
    void aCashFlowThatDoesNotExistIsRefusedByTheContractItself() {
        when(cashFlowApi.skeletonOf(CASH_FLOW_ID))
                .thenThrow(new CashFlowNotFoundException(CASH_FLOW_ID));

        assertThatThrownBy(() -> validator.validate(CASH_FLOW_ID, new CategoryName("Salary"), true))
                .as("it carries an ErrorCode and answers 404 on its own, so it must not be "
                        + "translated into this module's communication failure")
                .isInstanceOf(CashFlowNotFoundException.class);
    }

    private void givenSkeleton(List<CategoryNodeView> inflow, List<CategoryNodeView> outflow) {
        when(cashFlowApi.skeletonOf(any())).thenReturn(new CashFlowSkeletonView(
                CASH_FLOW_ID,
                UserId.of("U10000001"),
                CashFlowSkeletonView.LifecyclePhase.OPEN,
                YearMonth.of(2022, 1),
                YearMonth.of(2022, 1),
                new BankAccountId("PL61109010140000071219812874"),
                Currency.of("PLN"),
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
}
