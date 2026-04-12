package com.multi.vidulum.bank_data_ingestion.app.categorization;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.cashflow.domain.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ExistingCategoryStructure.
 */
class ExistingCategoryStructureTest {

    @Test
    @DisplayName("Should create empty structure")
    void shouldCreateEmptyStructure() {
        // when
        ExistingCategoryStructure structure = ExistingCategoryStructure.empty();

        // then
        assertThat(structure.isEmpty()).isTrue();
        assertThat(structure.inflowCategories()).isEmpty();
        assertThat(structure.outflowCategories()).isEmpty();
        assertThat(structure.allInflowNames()).isEmpty();
        assertThat(structure.allOutflowNames()).isEmpty();
        assertThat(structure.totalCategoryCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should create structure from CashFlowInfo with hierarchy")
    void shouldCreateStructureFromCashFlowInfoWithHierarchy() {
        // given
        CashFlowInfo cashFlowInfo = new CashFlowInfo(
                "CF123",
                CashFlowInfo.CashFlowStatus.OPEN,
                null, null,
                List.of(
                        new CashFlowInfo.CategoryInfo("Wynagrodzenie", null, Type.INFLOW, false, List.of(
                                new CashFlowInfo.CategoryInfo("Pensja", "Wynagrodzenie", Type.INFLOW, false, List.of()),
                                new CashFlowInfo.CategoryInfo("Premia", "Wynagrodzenie", Type.INFLOW, false, List.of())
                        )),
                        new CashFlowInfo.CategoryInfo("Zwroty", null, Type.INFLOW, false, List.of())
                ),
                List.of(
                        new CashFlowInfo.CategoryInfo("Żywność", null, Type.OUTFLOW, false, List.of(
                                new CashFlowInfo.CategoryInfo("Zakupy spożywcze", "Żywność", Type.OUTFLOW, false, List.of()),
                                new CashFlowInfo.CategoryInfo("Restauracje", "Żywność", Type.OUTFLOW, false, List.of())
                        )),
                        new CashFlowInfo.CategoryInfo("Transport", null, Type.OUTFLOW, false, List.of(
                                new CashFlowInfo.CategoryInfo("Paliwo", "Transport", Type.OUTFLOW, false, List.of())
                        ))
                ),
                Set.of(),
                0,
                null
        );

        // when
        ExistingCategoryStructure structure = ExistingCategoryStructure.fromCashFlowInfo(cashFlowInfo);

        // then
        assertThat(structure.isEmpty()).isFalse();

        // INFLOW categories
        assertThat(structure.inflowCategories()).hasSize(2);
        assertThat(structure.allInflowNames()).containsExactlyInAnyOrder(
                "Wynagrodzenie", "Pensja", "Premia", "Zwroty"
        );

        // OUTFLOW categories
        assertThat(structure.outflowCategories()).hasSize(2);
        assertThat(structure.allOutflowNames()).containsExactlyInAnyOrder(
                "Żywność", "Zakupy spożywcze", "Restauracje", "Transport", "Paliwo"
        );

        // Total count
        assertThat(structure.totalCategoryCount()).isEqualTo(9);
    }

    @Test
    @DisplayName("Should check category existence by type")
    void shouldCheckCategoryExistenceByType() {
        // given
        CashFlowInfo cashFlowInfo = new CashFlowInfo(
                "CF123",
                CashFlowInfo.CashFlowStatus.OPEN,
                null, null,
                List.of(
                        new CashFlowInfo.CategoryInfo("Wynagrodzenie", null, Type.INFLOW, false, List.of())
                ),
                List.of(
                        new CashFlowInfo.CategoryInfo("Żywność", null, Type.OUTFLOW, false, List.of())
                ),
                Set.of(),
                0,
                null
        );
        ExistingCategoryStructure structure = ExistingCategoryStructure.fromCashFlowInfo(cashFlowInfo);

        // then - correct type
        assertThat(structure.categoryExists("Wynagrodzenie", Type.INFLOW)).isTrue();
        assertThat(structure.categoryExists("Żywność", Type.OUTFLOW)).isTrue();

        // then - wrong type (category exists but wrong type)
        assertThat(structure.categoryExists("Wynagrodzenie", Type.OUTFLOW)).isFalse();
        assertThat(structure.categoryExists("Żywność", Type.INFLOW)).isFalse();

        // then - non-existent category
        assertThat(structure.categoryExists("Transport", Type.OUTFLOW)).isFalse();
    }

    @Test
    @DisplayName("Should check category existence regardless of type")
    void shouldCheckCategoryExistenceRegardlessOfType() {
        // given
        CashFlowInfo cashFlowInfo = new CashFlowInfo(
                "CF123",
                CashFlowInfo.CashFlowStatus.OPEN,
                null, null,
                List.of(
                        new CashFlowInfo.CategoryInfo("Wynagrodzenie", null, Type.INFLOW, false, List.of())
                ),
                List.of(
                        new CashFlowInfo.CategoryInfo("Żywność", null, Type.OUTFLOW, false, List.of())
                ),
                Set.of(),
                0,
                null
        );
        ExistingCategoryStructure structure = ExistingCategoryStructure.fromCashFlowInfo(cashFlowInfo);

        // then
        assertThat(structure.categoryExists("Wynagrodzenie")).isTrue();
        assertThat(structure.categoryExists("Żywność")).isTrue();
        assertThat(structure.categoryExists("Transport")).isFalse();
    }

    @Test
    @DisplayName("Should get all category names as flat set")
    void shouldGetAllCategoryNamesAsFlatSet() {
        // given
        CashFlowInfo cashFlowInfo = new CashFlowInfo(
                "CF123",
                CashFlowInfo.CashFlowStatus.OPEN,
                null, null,
                List.of(
                        new CashFlowInfo.CategoryInfo("Inflow1", null, Type.INFLOW, false, List.of())
                ),
                List.of(
                        new CashFlowInfo.CategoryInfo("Outflow1", null, Type.OUTFLOW, false, List.of())
                ),
                Set.of(),
                0,
                null
        );
        ExistingCategoryStructure structure = ExistingCategoryStructure.fromCashFlowInfo(cashFlowInfo);

        // when
        Set<String> allNames = structure.getAllCategoryNames();

        // then
        assertThat(allNames).containsExactlyInAnyOrder("Inflow1", "Outflow1");
    }

    @Test
    @DisplayName("Should preserve hierarchy in CategoryNode")
    void shouldPreserveHierarchyInCategoryNode() {
        // given
        CashFlowInfo cashFlowInfo = new CashFlowInfo(
                "CF123",
                CashFlowInfo.CashFlowStatus.OPEN,
                null, null,
                List.of(),
                List.of(
                        new CashFlowInfo.CategoryInfo("Żywność", null, Type.OUTFLOW, false, List.of(
                                new CashFlowInfo.CategoryInfo("Zakupy spożywcze", "Żywność", Type.OUTFLOW, false, List.of()),
                                new CashFlowInfo.CategoryInfo("Restauracje", "Żywność", Type.OUTFLOW, false, List.of())
                        ))
                ),
                Set.of(),
                0,
                null
        );

        // when
        ExistingCategoryStructure structure = ExistingCategoryStructure.fromCashFlowInfo(cashFlowInfo);

        // then
        assertThat(structure.outflowCategories()).hasSize(1);

        ExistingCategoryStructure.CategoryNode parentNode = structure.outflowCategories().get(0);
        assertThat(parentNode.name()).isEqualTo("Żywność");
        assertThat(parentNode.hasSubCategories()).isTrue();
        assertThat(parentNode.subCategories()).hasSize(2);
        assertThat(parentNode.subCategories().get(0).name()).isEqualTo("Zakupy spożywcze");
        assertThat(parentNode.subCategories().get(1).name()).isEqualTo("Restauracje");
    }

    @Test
    @DisplayName("Should handle null CashFlowInfo")
    void shouldHandleNullCashFlowInfo() {
        // when
        ExistingCategoryStructure structure = ExistingCategoryStructure.fromCashFlowInfo(null);

        // then
        assertThat(structure.isEmpty()).isTrue();
        assertThat(structure).isEqualTo(ExistingCategoryStructure.empty());
    }

    @Test
    @DisplayName("CategoryNode should get all names including nested")
    void categoryNodeShouldGetAllNamesIncludingNested() {
        // given
        ExistingCategoryStructure.CategoryNode node = new ExistingCategoryStructure.CategoryNode(
                "Parent",
                List.of(
                        new ExistingCategoryStructure.CategoryNode("Child1"),
                        new ExistingCategoryStructure.CategoryNode("Child2", List.of(
                                new ExistingCategoryStructure.CategoryNode("GrandChild")
                        ))
                )
        );

        // when
        List<String> names = node.getAllNames();

        // then
        assertThat(names).containsExactlyInAnyOrder("Parent", "Child1", "Child2", "GrandChild");
    }
}
