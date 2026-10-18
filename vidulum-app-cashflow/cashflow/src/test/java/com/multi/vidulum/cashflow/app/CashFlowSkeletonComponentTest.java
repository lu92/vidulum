package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.CategoryNodeView;
import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow.app.commands.comment.create.CreateCategoryCommand;
import com.multi.vidulum.cashflow.api.HistoricalCashChange;
import com.multi.vidulum.cashflow.app.commands.importhistorical.ImportHistoricalCashChangeCommand;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
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
        CashFlowModuleApiImpl api = new CashFlowModuleApiImpl(new OnlyFindById(null), new CommandGateway());

        assertThatThrownBy(() -> api.skeletonOf(CASH_FLOW_ID))
                .isInstanceOf(CashFlowNotFoundException.class)
                .hasMessageContaining("CF10000001");
    }

    @Test
    void createCategoryPassesTheImportFlagStraightThroughToTheAggregate() {
        CapturingHandler handler = new CapturingHandler();
        CommandGateway gateway = new CommandGateway();
        gateway.registerCommandHandler(handler);
        CashFlowModuleApiImpl api = new CashFlowModuleApiImpl(new OnlyFindById(null), gateway);

        api.createCategory(CASH_FLOW_ID, new CategoryName("Bonus"), new CategoryName("Salary"),
                Type.INFLOW, true);

        assertThat(handler.received)
                .as("one command, carrying exactly what the caller said - the flag included")
                .usingRecursiveComparison()
                .isEqualTo(new CreateCategoryCommand(
                        CASH_FLOW_ID, new CategoryName("Salary"), new CategoryName("Bonus"),
                        Type.INFLOW, true));
    }

    @Test
    void createCategoryWithoutTheImportFlagSendsAUserInitiatedCommand() {
        CapturingHandler handler = new CapturingHandler();
        CommandGateway gateway = new CommandGateway();
        gateway.registerCommandHandler(handler);
        CashFlowModuleApiImpl api = new CashFlowModuleApiImpl(new OnlyFindById(null), gateway);

        api.createCategory(CASH_FLOW_ID, new CategoryName("Salary"), null, Type.INFLOW, false);

        assertThat(handler.received.isImportOperation())
                .as("false must reach the handler as false - it is what makes a SETUP cash flow refuse")
                .isFalse();
        assertThat(handler.received.parentCategoryName())
                .as("a caller's null must not reach the domain as null. The contract says null means "
                        + "top-level; the domain says NOT_DEFINED, and CategoryCreatedEventHandler "
                        + "calls equals() on it - so forwarding the null unchanged killed the "
                        + "forecast projection with an NPE while the write itself succeeded. This "
                        + "assertion used to require exactly that, which is how a defect stayed "
                        + "pinned by a green test")
                .isEqualTo(CategoryName.NOT_DEFINED);
    }

    @Test
    void createCategoryGoesThroughTheAggregateRatherThanTheRepository() {
        CommandGateway gateway = new CommandGateway();
        CashFlowModuleApiImpl api = new CashFlowModuleApiImpl(new OnlyFindById(null), gateway);

        assertThatThrownBy(() -> api.createCategory(
                CASH_FLOW_ID, new CategoryName("Salary"), null, Type.INFLOW, true))
                .as("no handler registered, so the write had nowhere to go - which is the point: "
                        + "it is not reaching for the repository and reimplementing the rules")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No command handler registered");
    }

    @Test
    void importHistoricalPassesMoneyAndTimeThroughUntouched() {
        CapturingImportHandler handler = new CapturingImportHandler();
        CommandGateway gateway = new CommandGateway();
        gateway.registerCommandHandler(handler);
        CashFlowModuleApiImpl api = new CashFlowModuleApiImpl(new OnlyFindById(null), gateway);

        // A trailing zero the old round trip dropped, and a time of day it threw away. Note what
        // this does NOT claim: BigDecimal.valueOf(double) preserves the value for any realistic
        // amount, so the loss is scale, not pennies - 1234.50 came back as 1234.5.
        Money amount = Money.of(new java.math.BigDecimal("1234.50"), "PLN");
        ZonedDateTime paidAt = ZonedDateTime.parse("2023-03-14T13:45:30+01:00[Europe/Warsaw]");

        api.importHistorical(CASH_FLOW_ID, new HistoricalCashChange(
                new CategoryName("Groceries"), "Biedronka", "weekly shop",
                amount, Type.OUTFLOW, paidAt, paidAt, false, "BANK-TX-1"));

        assertThat(handler.received.money().getAmount())
                .as("scale included - the port used to send amount.doubleValue(), which turned "
                        + "1234.50 into 1234.5")
                .isEqualTo(new java.math.BigDecimal("1234.50"));
        assertThat(handler.received.money().getAmount().scale())
                .as("two decimal places, as the bank reported them")
                .isEqualTo(2);
        assertThat(handler.received.paidDate())
                .as("time of day and zone survive - the port sent LocalDate and the client "
                        + "rebuilt midnight in the server's zone")
                .isEqualTo(paidAt);
        assertThat(handler.received.paidDate().getZone())
                .isEqualTo(java.time.ZoneId.of("Europe/Warsaw"));
    }

    @Test
    void importHistoricalWrapsNameAndDescriptionOnCashflowsSideOfTheBoundary() {
        CapturingImportHandler handler = new CapturingImportHandler();
        CommandGateway gateway = new CommandGateway();
        gateway.registerCommandHandler(handler);
        CashFlowModuleApiImpl api = new CashFlowModuleApiImpl(new OnlyFindById(null), gateway);

        api.importHistorical(CASH_FLOW_ID, new HistoricalCashChange(
                new CategoryName("Salary"), "March salary", "from employer",
                Money.of(8000, "PLN"), Type.INFLOW,
                ZonedDateTime.parse("2023-03-28T09:00:00Z[UTC]"),
                ZonedDateTime.parse("2023-03-28T09:00:00Z[UTC]"), true, "BANK-TX-2"));

        assertThat(handler.received.name())
                .as("Name lives in cashflow.domain, where a consumer may not look")
                .isEqualTo(new Name("March salary"));
        assertThat(handler.received.description()).isEqualTo(new Description("from employer"));
        assertThat(handler.received.selfTransfer()).isTrue();
    }

    // ---------------------------------------------------------------- fixture

    private static CashFlowSkeletonView skeletonOf(CashFlow cashFlow) {
        return new CashFlowModuleApiImpl(new OnlyFindById(cashFlow), new CommandGateway()).skeletonOf(CASH_FLOW_ID);
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
                                false,
                                "BANK-TX-%05d".formatted(i))));
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

    private static final class CapturingImportHandler
            implements CommandHandler<ImportHistoricalCashChangeCommand, CashChangeId> {

        private ImportHistoricalCashChangeCommand received;

        @Override
        public CashChangeId handle(ImportHistoricalCashChangeCommand command) {
            this.received = command;
            return CashChangeId.of("CC1000000001");
        }
    }

    /**
     * A named class, not a lambda: {@code CommandGateway} finds the handler's command type by
     * reflecting over {@code handle}'s declared parameter, and a lambda's is erased to
     * {@code Command}.
     */
    private static final class CapturingHandler implements CommandHandler<CreateCategoryCommand, Void> {

        private CreateCategoryCommand received;

        @Override
        public Void handle(CreateCategoryCommand command) {
            this.received = command;
            return null;
        }
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
