package com.multi.vidulum.cashflow.api.view;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.UserId;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tree operations the contract took over from {@code CashFlowInfo}.
 *
 * <p>They moved because <b>deciding what counts as "the same category" is cashflow's policy, not a
 * consumer's string handling</b>. The view is typed on purpose - a name read out of a bank
 * statement is not a {@link CategoryName} until something says it is - and
 * {@code findCategoryIgnoringCase} is that something. Before this, two handlers in
 * bank-data-ingestion lowercased and compared on their own, against a {@code Set<String>}.
 *
 * <p>The one-sided overload is the one production used, and these tests say why: a cash flow may
 * hold the same word in both directions.
 */
class CashFlowSkeletonViewTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");

    private static CategoryNodeView node(String name, CategoryNodeView... children) {
        return new CategoryNodeView(new CategoryName(name), null, false, List.of(children));
    }

    private static CategoryNodeView child(String parent, String name) {
        return new CategoryNodeView(new CategoryName(name), new CategoryName(parent), false, List.of());
    }

    /** "Transfer" on both sides, plus a nested expense tree. */
    private static CashFlowSkeletonView skeleton() {
        return new CashFlowSkeletonView(
                CASH_FLOW_ID,
                UserId.of("U10000001"),
                CashFlowSkeletonView.LifecyclePhase.SETUP,
                YearMonth.of(2022, 1),
                YearMonth.of(2021, 10),
                List.of(node("Salary", child("Salary", "Bonus")), node("Transfer")),
                List.of(node("Home", child("Home", "Rent"), child("Home", "Utilities")),
                        node("Transfer")));
    }

    @Test
    void findsACategoryWhateverCaseTheBankUsed() {
        assertThat(skeleton().findCategoryIgnoringCase("  rENT  "))
                .as("trimmed and case-folded, because a statement is not a catalogue")
                .contains(new CategoryName("Rent"));
    }

    @Test
    void answersWithTheStoredSpellingNotTheQueried() {
        assertThat(skeleton().findCategoryIgnoringCase("utilities"))
                .get()
                .extracting(CategoryName::name)
                .as("the cash flow's own spelling - which is the point of asking cashflow")
                .isEqualTo("Utilities");
    }

    @Test
    void findsNothingForABlankOrUnknownName() {
        assertThat(skeleton().findCategoryIgnoringCase("   ")).isEmpty();
        assertThat(skeleton().findCategoryIgnoringCase(null)).isEmpty();
        assertThat(skeleton().findCategoryIgnoringCase("Groceries")).isEmpty();
    }

    @Test
    void theOneSidedLookupDoesNotCrossDirections() {
        CashFlowSkeletonView skeleton = skeleton();

        assertThat(skeleton.findCategoryIgnoringCase("rent", Type.OUTFLOW))
                .contains(new CategoryName("Rent"));
        assertThat(skeleton.findCategoryIgnoringCase("rent", Type.INFLOW))
                .as("Rent is an expense; asking for it as income must not find it")
                .isEmpty();
        assertThat(skeleton.findCategoryIgnoringCase("transfer", Type.INFLOW))
                .as("the same word exists on both sides and each side has its own")
                .contains(new CategoryName("Transfer"));
    }

    @Test
    void findsTheParentOfANestedCategory() {
        assertThat(skeleton().parentOf(new CategoryName("rent"), Type.OUTFLOW))
                .as("case-insensitive, like the lookup next to it")
                .isEqualTo(new CategoryName("Home"));
        assertThat(skeleton().parentOf(new CategoryName("Bonus"), Type.INFLOW))
                .isEqualTo(new CategoryName("Salary"));
    }

    @Test
    void aTopLevelCategoryHasNoParent() {
        assertThat(skeleton().parentOf(new CategoryName("Home"), Type.OUTFLOW))
                .as("null means root - the encoding CategoryNodeView.isRoot() already uses")
                .isNull();
        assertThat(skeleton().parentOf(new CategoryName("Nonexistent"), Type.OUTFLOW))
                .as("and an unknown category is also null, which is why a caller that cares "
                        + "asks findCategoryIgnoringCase first")
                .isNull();
        assertThat(skeleton().parentOf(null, Type.OUTFLOW)).isNull();
    }

    @Test
    void typedNamesKeepTheTwoSidesApart() {
        assertThat(skeleton().typedCategoryNames())
                .as("subcategories included, and Transfer appears once per direction")
                .containsExactlyInAnyOrder(
                        new TypedCategoryName(new CategoryName("Salary"), Type.INFLOW),
                        new TypedCategoryName(new CategoryName("Bonus"), Type.INFLOW),
                        new TypedCategoryName(new CategoryName("Transfer"), Type.INFLOW),
                        new TypedCategoryName(new CategoryName("Home"), Type.OUTFLOW),
                        new TypedCategoryName(new CategoryName("Rent"), Type.OUTFLOW),
                        new TypedCategoryName(new CategoryName("Utilities"), Type.OUTFLOW),
                        new TypedCategoryName(new CategoryName("Transfer"), Type.OUTFLOW));
    }

    @Test
    void untypedNamesCollapseTheTwoSides() {
        assertThat(skeleton().allCategoryNames())
                .as("Transfer once, because a Set<CategoryName> cannot hold a direction - which is "
                        + "exactly why typedCategoryNames() exists")
                .containsExactlyInAnyOrder(
                        new CategoryName("Salary"), new CategoryName("Bonus"),
                        new CategoryName("Transfer"), new CategoryName("Home"),
                        new CategoryName("Rent"), new CategoryName("Utilities"));
    }
}
