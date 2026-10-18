package com.multi.vidulum.bank_data_ingestion.app.categorization;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.CategoryNodeView;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this record is for: turning the shape cashflow's contract answers with into the shape the
 * AI prompt builder reads - a tree of plain names plus a flat set per side.
 *
 * <p>These tests used to drive {@code fromCashFlowInfo}, a factory with <b>no production caller</b>:
 * {@code AiCategorizeCommandHandler} has been using {@code from(skeleton)} for a while, and that one
 * had no test at all. So the coverage sat on the dead twin. Two cases are gone rather than ported -
 * they asserted {@code CashFlowInfo.getAllTypedCategoryKeys}, which was never about this record and
 * is covered by {@code CashFlowSkeletonViewTest} in the contract's own module.
 */
class ExistingCategoryStructureTest {

    @Test
    @DisplayName("Should create empty structure")
    void shouldCreateEmptyStructure() {
        ExistingCategoryStructure structure = ExistingCategoryStructure.empty();

        assertThat(structure.isEmpty()).isTrue();
        assertThat(structure.inflowCategories()).isEmpty();
        assertThat(structure.outflowCategories()).isEmpty();
        assertThat(structure.allInflowNames()).isEmpty();
        assertThat(structure.allOutflowNames()).isEmpty();
        assertThat(structure.totalCategoryCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should create structure from a skeleton, hierarchy included")
    void shouldCreateStructureFromSkeletonWithHierarchy() {
        ExistingCategoryStructure structure = ExistingCategoryStructure.from(skeleton(
                List.of(
                        node("Wynagrodzenie", "Pensja", "Premia"),
                        node("Inwestycje", "Dywidendy")),
                List.of(
                        node("Żywność", "Zakupy spożywcze", "Restauracje"),
                        node("Transport", "Paliwo"))));

        assertThat(structure.isEmpty()).isFalse();

        assertThat(structure.inflowCategories()).hasSize(2);
        assertThat(structure.allInflowNames())
                .containsExactlyInAnyOrder("Wynagrodzenie", "Pensja", "Premia", "Inwestycje", "Dywidendy");

        assertThat(structure.outflowCategories()).hasSize(2);
        assertThat(structure.allOutflowNames())
                .containsExactlyInAnyOrder("Żywność", "Zakupy spożywcze", "Restauracje", "Transport", "Paliwo");

        assertThat(structure.totalCategoryCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("Should check category existence by type")
    void shouldCheckCategoryExistenceByType() {
        ExistingCategoryStructure structure = ExistingCategoryStructure.from(skeleton(
                List.of(node("Wynagrodzenie")),
                List.of(node("Żywność"))));

        assertThat(structure.categoryExists("Wynagrodzenie", Type.INFLOW)).isTrue();
        assertThat(structure.categoryExists("Żywność", Type.OUTFLOW)).isTrue();

        assertThat(structure.categoryExists("Wynagrodzenie", Type.OUTFLOW)).isFalse();
        assertThat(structure.categoryExists("Żywność", Type.INFLOW)).isFalse();

        assertThat(structure.categoryExists("Transport", Type.OUTFLOW)).isFalse();
    }

    @Test
    @DisplayName("Should check category existence regardless of type")
    void shouldCheckCategoryExistenceRegardlessOfType() {
        ExistingCategoryStructure structure = ExistingCategoryStructure.from(skeleton(
                List.of(node("Wynagrodzenie")),
                List.of(node("Żywność"))));

        assertThat(structure.categoryExists("Wynagrodzenie")).isTrue();
        assertThat(structure.categoryExists("Żywność")).isTrue();
        assertThat(structure.categoryExists("Transport")).isFalse();
    }

    @Test
    @DisplayName("Should get all category names as flat set")
    void shouldGetAllCategoryNamesAsFlatSet() {
        ExistingCategoryStructure structure = ExistingCategoryStructure.from(skeleton(
                List.of(node("Wynagrodzenie", "Pensja")),
                List.of(node("Żywność", "Zakupy"))));

        assertThat(structure.getAllCategoryNames())
                .containsExactlyInAnyOrder("Wynagrodzenie", "Pensja", "Żywność", "Zakupy");
    }

    @Test
    @DisplayName("One name on both sides appears once in the flat set, which is why the typed "
            + "lookups exist")
    void theFlatSetCollapsesANameThatExistsOnBothSides() {
        ExistingCategoryStructure structure = ExistingCategoryStructure.from(skeleton(
                List.of(node("Inne")),
                List.of(node("Inne"), node("Żywność"))));

        assertThat(structure.getAllCategoryNames()).containsExactlyInAnyOrder("Inne", "Żywność");
        assertThat(structure.categoryExists("Inne", Type.INFLOW)).isTrue();
        assertThat(structure.categoryExists("Inne", Type.OUTFLOW)).isTrue();
        assertThat(structure.categoryExists("Żywność", Type.INFLOW)).isFalse();
    }

    @Test
    @DisplayName("Should preserve hierarchy in CategoryNode")
    void shouldPreserveHierarchyInCategoryNode() {
        ExistingCategoryStructure structure = ExistingCategoryStructure.from(skeleton(
                List.of(),
                List.of(node("Żywność", "Zakupy spożywcze", "Restauracje"))));

        assertThat(structure.outflowCategories()).singleElement().satisfies(parent -> {
            assertThat(parent.name()).isEqualTo("Żywność");
            assertThat(parent.subCategories()).hasSize(2);
            assertThat(parent.subCategories())
                    .extracting(ExistingCategoryStructure.CategoryNode::name)
                    .containsExactly("Zakupy spożywcze", "Restauracje");
        });
    }

    @Test
    @DisplayName("Should handle a null skeleton")
    void shouldHandleNullSkeleton() {
        ExistingCategoryStructure structure = ExistingCategoryStructure.from(null);

        assertThat(structure.isEmpty()).isTrue();
        assertThat(structure.totalCategoryCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("CategoryNode should get all names including nested")
    void categoryNodeShouldGetAllNamesIncludingNested() {
        ExistingCategoryStructure.CategoryNode node = new ExistingCategoryStructure.CategoryNode(
                "Żywność",
                List.of(
                        new ExistingCategoryStructure.CategoryNode("Zakupy spożywcze"),
                        new ExistingCategoryStructure.CategoryNode("Restauracje")));

        assertThat(node.getAllNames())
                .containsExactlyInAnyOrder("Żywność", "Zakupy spożywcze", "Restauracje");
    }

    private static CashFlowSkeletonView skeleton(
            List<CategoryNodeView> inflow,
            List<CategoryNodeView> outflow) {
        return new CashFlowSkeletonView(
                CashFlowId.of("CF10000001"),
                UserId.of("U10000001"),
                CashFlowSkeletonView.LifecyclePhase.OPEN,
                YearMonth.of(2026, 3),
                YearMonth.of(2026, 1),
                inflow,
                outflow);
    }

    /**
     * A root category with the given children. {@link CategoryNodeView} checks that a child names
     * its parent, so the parent link is filled in here rather than left to each case.
     */
    private static CategoryNodeView node(String name, String... subCategories) {
        CategoryName parent = new CategoryName(name);
        List<CategoryNodeView> children = java.util.Arrays.stream(subCategories)
                .map(sub -> new CategoryNodeView(new CategoryName(sub), parent, false, List.of()))
                .toList();
        return new CategoryNodeView(parent, null, false, children);
    }
}
