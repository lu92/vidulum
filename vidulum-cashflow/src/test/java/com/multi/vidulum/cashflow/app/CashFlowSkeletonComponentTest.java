package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.CategoryNodeView;
import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the cashflow contract answers, asserted whole.
 *
 * <p>Component level: the real implementation over a repository that only knows how to find one
 * aggregate. No Spring, no Mongo - the mapping from snapshot to view is arithmetic over data, and
 * the four integration tests of the staging preview already cover the wiring.
 *
 * <p>The expected views are built with constructors, never builders, so that adding a field to
 * {@link CashFlowSkeletonView} stops this test from compiling instead of letting it pass with the
 * new field unasserted.
 */
class CashFlowSkeletonComponentTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final UserId OWNER = UserId.of("U10000001");
    private static final ZonedDateTime SOME_TIME = ZonedDateTime.parse("2026-10-16T00:00:00Z");
    private static final YearMonth START = YearMonth.of(2023, 1);
    private static final YearMonth ACTIVE = YearMonth.of(2026, 10);

    @Test
    void answersOwnerPeriodsAndTheCategoryTree() {
        CashFlowSkeletonView skeleton = skeletonOf(cashFlow(
                CashFlow.CashFlowStatus.OPEN,
                List.of(category("Salary")),
                List.of(parent("Home", category("Rent"), category("Energy")))));

        assertThat(skeleton)
                .usingRecursiveComparison()
                .isEqualTo(new CashFlowSkeletonView(
                        CASH_FLOW_ID,
                        OWNER,
                        CashFlowSkeletonView.LifecyclePhase.OPEN,
                        ACTIVE,
                        START,
                        List.of(new CategoryNodeView(new CategoryName("Salary"), null, false, List.of())),
                        List.of(new CategoryNodeView(
                                new CategoryName("Home"), null, false,
                                List.of(
                                        new CategoryNodeView(new CategoryName("Rent"),
                                                new CategoryName("Home"), false, List.of()),
                                        new CategoryNodeView(new CategoryName("Energy"),
                                                new CategoryName("Home"), false, List.of()))))));
    }

    /**
     * The promise this method exists for: the answer does not grow with the history. A cash flow
     * with transactions in it answers exactly as the same cash flow without them.
     */
    @Test
    void carriesNoTransactionsHoweverManyThereAre() {
        CashFlowSnapshot withoutHistory = snapshot(
                CashFlow.CashFlowStatus.OPEN, Map.of(), List.of(category("Salary")), List.of());
        CashFlowSnapshot withHistory = snapshot(
                CashFlow.CashFlowStatus.OPEN, cashChanges(500), List.of(category("Salary")), List.of());

        assertThat(skeletonOf(CashFlow.from(withHistory)))
                .usingRecursiveComparison()
                .isEqualTo(skeletonOf(CashFlow.from(withoutHistory)));
    }

    @Test
    void keepsArchivedCategoriesAndSaysSo() {
        CashFlowSkeletonView skeleton = skeletonOf(cashFlow(
                CashFlow.CashFlowStatus.OPEN,
                List.of(),
                List.of(archived("Old subscription"))));

        assertThat(skeleton.outflowCategories())
                .as("an archived category takes no new transactions, but historical ones point at it")
                .containsExactly(new CategoryNodeView(
                        new CategoryName("Old subscription"), null, true, List.of()));
    }

    @Test
    void reportsTheLifecyclePhase() {
        assertThat(skeletonOf(cashFlow(CashFlow.CashFlowStatus.SETUP, List.of(), List.of())).isInSetup()).isTrue();
        assertThat(skeletonOf(cashFlow(CashFlow.CashFlowStatus.OPEN, List.of(), List.of())).isOpen()).isTrue();
        assertThat(skeletonOf(cashFlow(CashFlow.CashFlowStatus.CLOSED, List.of(), List.of())).isClosed()).isTrue();
    }

    @Test
    void namesAreTypedAndCountSubcategoriesIn() {
        CashFlowSkeletonView skeleton = skeletonOf(cashFlow(
                CashFlow.CashFlowStatus.OPEN,
                List.of(category("Rent")),
                List.of(parent("Home", category("Rent")))));

        assertThat(skeleton.allCategoryNames())
                .containsExactlyInAnyOrder(
                        new CategoryName("Rent"), new CategoryName("Home"));
        assertThat(skeleton.inflowCategoryNames())
                .as("the same word on both sides is two different categories")
                .containsExactly(new CategoryName("Rent"));
        assertThat(skeleton.outflowCategoryNames())
                .containsExactlyInAnyOrder(new CategoryName("Home"), new CategoryName("Rent"));
    }

    @Test
    void refusesWhenThereIsNoSuchCashFlow() {
        CashFlowModuleApiImpl api = new CashFlowModuleApiImpl(new OnlyFindById(null));

        assertThatThrownBy(() -> api.skeletonOf(CASH_FLOW_ID))
                .isInstanceOf(CashFlowNotFoundException.class)
                .hasMessageContaining("CF10000001");
    }

    // ---------------------------------------------------------------- fixture

    private static CashFlowSkeletonView skeletonOf(CashFlow cashFlow) {
        return new CashFlowModuleApiImpl(new OnlyFindById(cashFlow)).skeletonOf(CASH_FLOW_ID);
    }

    private static CashFlow cashFlow(CashFlow.CashFlowStatus status,
                                     List<Category> inflow,
                                     List<Category> outflow) {
        return CashFlow.from(snapshot(status, Map.of(), inflow, outflow));
    }

    private static CashFlowSnapshot snapshot(CashFlow.CashFlowStatus status,
                                             Map<CashChangeId, com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot> cashChanges,
                                             List<Category> inflow,
                                             List<Category> outflow) {
        return new CashFlowSnapshot(
                CASH_FLOW_ID,
                OWNER,
                new Name("Nest Bank"),
                new Description("current account"),
                BankAccount.fromIban("Nest Bank", "PL61109010140000071219812874",
                        Currency.of("PLN"), Money.of(0, "PLN"), null),
                status,
                cashChanges,
                START,
                ACTIVE,
                Money.of(0, "PLN"),
                inflow,
                outflow,
                SOME_TIME,
                SOME_TIME,
                null,
                new Checksum("irrelevant-to-the-skeleton"));
    }

    private static Map<CashChangeId, com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot> cashChanges(int howMany) {
        return java.util.stream.IntStream.range(0, howMany)
                .boxed()
                .collect(java.util.stream.Collectors.toMap(
                        i -> CashChangeId.of("CC10000%05d".formatted(i)),
                        i -> new com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot(
                                CashChangeId.of("CC10000%05d".formatted(i)),
                                new Name("transaction " + i),
                                new Description("imported"),
                                Money.of(10, "PLN"),
                                com.multi.vidulum.cashflow.api.Type.OUTFLOW,
                                new CategoryName("Uncategorized"),
                                com.multi.vidulum.cashflow.domain.CashChangeStatus.CONFIRMED,
                                SOME_TIME,
                                SOME_TIME,
                                SOME_TIME,
                                null,
                                false)));
    }

    private static Category category(String name) {
        return Category.builder()
                .categoryName(new CategoryName(name))
                .subCategories(List.of())
                .isModifiable(true)
                .build();
    }

    private static Category parent(String name, Category... children) {
        return Category.builder()
                .categoryName(new CategoryName(name))
                .subCategories(List.of(children))
                .isModifiable(true)
                .build();
    }

    private static Category archived(String name) {
        return Category.builder()
                .categoryName(new CategoryName(name))
                .subCategories(List.of())
                .isModifiable(true)
                .archived(true)
                .build();
    }

    /**
     * Everything this contract needs from storage is one lookup. The rest throws rather than
     * returning something plausible, so a future method that reaches further fails loudly here.
     */
    private record OnlyFindById(CashFlow cashFlow) implements DomainCashFlowRepository {

        @Override
        public Optional<CashFlow> findById(CashFlowId id) {
            return Optional.ofNullable(cashFlow);
        }

        @Override
        public CashFlow save(CashFlow aggregate) {
            throw new UnsupportedOperationException("the skeleton is read-only");
        }

        @Override
        public List<DomainEvent> findDomainEvents(CashFlowId cashFlowId) {
            throw new UnsupportedOperationException("not needed to answer the skeleton");
        }

        @Override
        public List<CashFlow> findDetailsByUserId(UserId userId) {
            throw new UnsupportedOperationException("not needed to answer the skeleton");
        }

        @Override
        public List<CashFlow> findOpenCashFlowsNeedingRollover(YearMonth targetPeriod) {
            throw new UnsupportedOperationException("not needed to answer the skeleton");
        }

        @Override
        public boolean existsByUserIdAndName(UserId userId, String name) {
            throw new UnsupportedOperationException("not needed to answer the skeleton");
        }
    }
}
