package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.REJECTED;
import static java.util.Objects.isNull;

@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashFlow implements Aggregate<CashFlowId, CashFlowSnapshot> {
    private CashFlowId cashFlowId;
    private UserId userId;
    private Name name;
    private Description description;
    private BankAccount bankAccount;
    private CashFlowStatus status;
    private Map<CashChangeId, CashChange> cashChanges;
    private YearMonth startPeriod;
    private YearMonth activePeriod;
    private Money initialBalance;
    private List<Category> inflowCategories;
    private List<Category> outflowCategories;
    private ZonedDateTime created;
    private ZonedDateTime lastModification;
    private ZonedDateTime importCutoffDateTime;
    private Checksum lastMessageChecksum;
    private List<CashFlowEvent> uncommittedEvents = new LinkedList<>();


    @Override
    public CashFlowSnapshot getSnapshot() {
        Map<CashChangeId, CashChangeSnapshot> cashChangeSnapshotMap = cashChanges.values().stream()
                .map(cashChange -> new CashChangeSnapshot(
                        cashChange.getCashChangeId(),
                        cashChange.getName(),
                        cashChange.getDescription(),
                        cashChange.getMoney(),
                        cashChange.getType(),
                        cashChange.getCategoryName(),
                        cashChange.getStatus(),
                        cashChange.getCreated(),
                        cashChange.getDueDate(),
                        cashChange.getEndDate()
                )).collect(
                        Collectors.toUnmodifiableMap(
                                CashChangeSnapshot::cashChangeId,
                                Function.identity()
                        ));

        return new CashFlowSnapshot(
                cashFlowId,
                userId,
                name,
                description,
                bankAccount,
                status,
                cashChangeSnapshotMap,
                startPeriod,
                activePeriod,
                initialBalance,
                inflowCategories,
                outflowCategories,
                created,
                lastModification,
                importCutoffDateTime,
                lastMessageChecksum
        );
    }

    public static CashFlow from(CashFlowSnapshot snapshot) {
        Map<CashChangeId, CashChange> cashChanges = snapshot.cashChanges().values().stream()
                .map(cashChangeSnapshot -> CashChange.builder()
                        .cashChangeId(cashChangeSnapshot.cashChangeId())
                        .name(cashChangeSnapshot.name())
                        .description(cashChangeSnapshot.description())
                        .money(cashChangeSnapshot.money())
                        .type(cashChangeSnapshot.type())
                        .categoryName(cashChangeSnapshot.categoryName())
                        .status(cashChangeSnapshot.status())
                        .created(cashChangeSnapshot.created())
                        .dueDate(cashChangeSnapshot.dueDate())
                        .endDate(cashChangeSnapshot.endDate())
                        .build())
                .collect(Collectors.toMap(
                        CashChange::getCashChangeId,
                        Function.identity()
                ));

        return CashFlow.builder()
                .cashFlowId(snapshot.cashFlowId())
                .userId(snapshot.userId())
                .name(snapshot.name())
                .description(snapshot.description())
                .bankAccount(snapshot.bankAccount())
                .status(snapshot.status())
                .cashChanges(cashChanges)
                .startPeriod(snapshot.startPeriod())
                .activePeriod(snapshot.activePeriod())
                .initialBalance(snapshot.initialBalance())
                .inflowCategories(snapshot.inflowCategories())
                .outflowCategories(snapshot.outflowCategories())
                .created(snapshot.created())
                .lastModification(snapshot.lastModification())
                .importCutoffDateTime(snapshot.importCutoffDateTime())
                .lastMessageChecksum(snapshot.lastMessageChecksum())
                .build();
    }

    public void apply(CashFlowEvent.CashFlowCreatedEvent event) {
        LinkedList<Category> inflowCategories = new LinkedList<>();
        inflowCategories.add(new Category(
                new CategoryName("Uncategorized"),
                null,
                new LinkedList<>(),
                false
        ));
        LinkedList<Category> outflowCategories = new LinkedList<>();
        outflowCategories.add(new Category(
                new CategoryName("Uncategorized"),
                null,
                new LinkedList<>(),
                false
        ));
        this.cashFlowId = event.cashFlowId();
        this.userId = event.userId();
        this.name = event.name();
        this.description = event.description();
        this.bankAccount = event.bankAccount();
        this.status = CashFlowStatus.OPEN;
        this.cashChanges = new HashMap<>();
        this.startPeriod = YearMonth.from(event.created());  // For normal CashFlow, startPeriod = activePeriod
        this.activePeriod = YearMonth.from(event.created());
        this.initialBalance = event.bankAccount().balance();  // For normal CashFlow, initialBalance = current balance
        this.created = event.created();
        this.inflowCategories = inflowCategories;
        this.outflowCategories = outflowCategories;
        this.lastModification = null;
        this.lastMessageChecksum = calculateChecksum(event);
        this.uncommittedEvents = new LinkedList<>();
        this.uncommittedEvents.add(event);
    }

    /**
     * Creates a CashFlow in SETUP mode for historical data import.
     * The CashFlow will have status SETUP until activated.
     */
    public void apply(CashFlowEvent.CashFlowWithHistoryCreatedEvent event) {
        LinkedList<Category> inflowCategories = new LinkedList<>();
        inflowCategories.add(new Category(
                new CategoryName("Uncategorized"),
                null,
                new LinkedList<>(),
                false
        ));
        LinkedList<Category> outflowCategories = new LinkedList<>();
        outflowCategories.add(new Category(
                new CategoryName("Uncategorized"),
                null,
                new LinkedList<>(),
                false
        ));
        this.cashFlowId = event.cashFlowId();
        this.userId = event.userId();
        this.name = event.name();
        this.description = event.description();
        this.bankAccount = event.bankAccount();
        this.status = CashFlowStatus.SETUP;  // SETUP mode for historical import
        this.cashChanges = new HashMap<>();
        this.startPeriod = event.startPeriod();  // Historical start period
        this.activePeriod = event.activePeriod();
        this.initialBalance = event.initialBalance();  // Balance at start of startPeriod
        this.created = event.created();
        this.inflowCategories = inflowCategories;
        this.outflowCategories = outflowCategories;
        this.lastModification = null;
        this.lastMessageChecksum = calculateChecksum(event);
        this.uncommittedEvents = new LinkedList<>();
        this.uncommittedEvents.add(event);
    }

    public void apply(CashFlowEvent.ExpectedCashChangeAppendedEvent event) {
        CashChange cashChange = new CashChange(
                event.cashChangeId(),
                event.name(),
                event.description(),
                event.money(),
                event.type(),
                event.categoryName(),
                CashChangeStatus.PENDING,
                event.created(),
                event.dueDate(),
                null
        );
        cashChanges.put(cashChange.getSnapshot().cashChangeId(), cashChange);
        add(event);
    }

    public void apply(CashFlowEvent.PaidCashChangeAppendedEvent event) {
        // Validate: paidDate must be in the active period
        YearMonth paidDatePeriod = YearMonth.from(event.paidDate());
        if (!paidDatePeriod.equals(activePeriod)) {
            throw new PaidDateNotInActivePeriodException(event.paidDate(), activePeriod);
        }

        CashChange cashChange = new CashChange(
                event.cashChangeId(),
                event.name(),
                event.description(),
                event.money(),
                event.type(),
                event.categoryName(),
                CashChangeStatus.CONFIRMED,
                event.created(),
                event.dueDate(),
                event.paidDate()
        );
        cashChanges.put(cashChange.getSnapshot().cashChangeId(), cashChange);

        // Update bank balance
        if (Type.INFLOW.equals(event.type())) {
            bankAccount = bankAccount.withUpdatedBalance(bankAccount.balance().plus(event.money()));
        } else {
            bankAccount = bankAccount.withUpdatedBalance(bankAccount.balance().minus(event.money()));
        }

        add(event);
    }

    /**
     * Imports a historical cash change into a CashFlow in SETUP mode.
     * Historical transactions are imported as already CONFIRMED.
     * No active period validation - historical imports go to IMPORT_PENDING months.
     */
    public void apply(CashFlowEvent.HistoricalCashChangeImportedEvent event) {
        CashChange cashChange = new CashChange(
                event.cashChangeId(),
                event.name(),
                event.description(),
                event.money(),
                event.type(),
                event.categoryName(),
                CashChangeStatus.CONFIRMED,
                event.importedAt(),
                event.dueDate(),
                event.paidDate()
        );
        cashChanges.put(cashChange.getSnapshot().cashChangeId(), cashChange);

        // Note: Bank balance is NOT updated here because this is historical data.
        // The balance reconciliation happens during activation.

        add(event);
    }

    /**
     * Attests a historical import, transitioning CashFlow from SETUP to OPEN mode.
     * This marks the end of the historical import process.
     * The bank balance is updated to the confirmed balance from the event.
     * The importCutoffDateTime is set to mark the boundary between historical and new data.
     */
    public void apply(CashFlowEvent.HistoricalImportAttestedEvent event) {
        this.status = CashFlowStatus.OPEN;
        this.bankAccount = bankAccount.withUpdatedBalance(event.confirmedBalance());
        this.importCutoffDateTime = event.attestedAt();

        // If adjustment was created, add it as a cash change
        if (event.adjustmentCashChangeId() != null) {
            CashChange adjustmentCashChange = new CashChange(
                    event.adjustmentCashChangeId(),
                    new Name("Balance Adjustment"),
                    new Description("Automatic adjustment to reconcile balance difference"),
                    event.balanceDifference().abs(),
                    event.balanceDifference().isPositive() ? Type.INFLOW : Type.OUTFLOW,
                    new CategoryName("Uncategorized"),
                    CashChangeStatus.CONFIRMED,
                    event.attestedAt(),
                    event.attestedAt(),
                    event.attestedAt()
            );
            cashChanges.put(adjustmentCashChange.getCashChangeId(), adjustmentCashChange);
        }

        add(event);
    }

    /**
     * Rolls back (clears) all imported historical data from a CashFlow in SETUP mode.
     * This clears all cash changes and optionally resets categories to just Uncategorized.
     * The CashFlow remains in SETUP mode, ready for fresh import.
     */
    public void apply(CashFlowEvent.ImportRolledBackEvent event) {
        // Clear all cash changes
        this.cashChanges.clear();

        // Optionally reset categories to just Uncategorized
        if (event.categoriesDeleted()) {
            LinkedList<Category> newInflowCategories = new LinkedList<>();
            newInflowCategories.add(new Category(
                    new CategoryName("Uncategorized"),
                    null,
                    new LinkedList<>(),
                    false
            ));
            LinkedList<Category> newOutflowCategories = new LinkedList<>();
            newOutflowCategories.add(new Category(
                    new CategoryName("Uncategorized"),
                    null,
                    new LinkedList<>(),
                    false
            ));
            this.inflowCategories = newInflowCategories;
            this.outflowCategories = newOutflowCategories;
        }

        // CashFlow remains in SETUP mode
        add(event);
    }

    /**
     * Calculates the current balance based on initial balance and all confirmed cash changes.
     * Formula: initialBalance + sum(INFLOW amounts) - sum(OUTFLOW amounts)
     *
     * @return the calculated balance
     */
    public Money calculateCurrentBalance() {
        Money balance = initialBalance != null ? initialBalance : Money.zero(bankAccount.balance().getCurrency());

        for (CashChange cashChange : cashChanges.values()) {
            if (cashChange.getStatus() == CashChangeStatus.CONFIRMED) {
                if (Type.INFLOW.equals(cashChange.getType())) {
                    balance = balance.plus(cashChange.getMoney());
                } else {
                    balance = balance.minus(cashChange.getMoney());
                }
            }
        }

        return balance;
    }

    public void apply(CashFlowEvent.CashChangeConfirmedEvent event) {
        performOn(event.cashChangeId(), cashChange ->
                cashChange.onlyWhenIsPending(() -> {
                    cashChange.setStatus(CashChangeStatus.CONFIRMED);
                    cashChange.setEndDate(event.endDate());
                    if (Type.INFLOW.equals(cashChange.getType())) {
                        bankAccount = bankAccount.withUpdatedBalance(bankAccount.balance().plus(cashChange.getMoney()));
                    } else {
                        bankAccount = bankAccount.withUpdatedBalance(bankAccount.balance().minus(cashChange.getMoney()));
                    }
                }));
        add(event);
    }

    public void apply(CashFlowEvent.CashChangeEditedEvent event) {
        performOn(event.cashChangeId(), cashChange ->
                cashChange.onlyWhenIsPending(() -> {
                    cashChange.setName(event.name());
                    cashChange.setDescription(event.description());
                    cashChange.setMoney(event.money());
                    cashChange.setDueDate(event.dueDate());
                }));
        add(event);
    }

    public void apply(CashFlowEvent.CashChangeRejectedEvent event) {
        performOn(event.cashChangeId(), cashChange -> {
            cashChange.onlyWhenIsPending(() -> cashChange.setStatus(REJECTED));
        });
        add(event);
    }

    public void apply(CashFlowEvent.MonthAttestedEvent event) {
        if (!activePeriod.plusMonths(1).equals(event.period())) {
            throw new IllegalArgumentException("Active period [%s] and requested attestation period: [%s] - invalid period!".formatted(activePeriod, event.period()));
        }

        activePeriod = event.period();
        bankAccount = bankAccount.withUpdatedBalance(event.currentMoney());
        add(event);
    }

    public void apply(CashFlowEvent.CategoryCreatedEvent event) {
        List<Category> categories = Type.INFLOW.equals(event.type()) ? inflowCategories : outflowCategories;

        Category newCategory = new Category(
                event.categoryName(),
                null,
                new LinkedList<>(),
                true);

        if (event.parentCategoryName().isDefined()) {
            Category parentCategory = findCategoryByName(event.parentCategoryName(), categories)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid parent category-id!"));

            parentCategory.getSubCategories().add(newCategory);
        } else {
            categories.add(newCategory);
        }
        add(event);
    }

    public void apply(CashFlowEvent.BudgetingSetEvent event) {
        List<Category> categories = Type.INFLOW.equals(event.categoryType()) ? inflowCategories : outflowCategories;
        Category category = findCategoryByName(event.categoryName(), categories)
                .orElseThrow(() -> new CategoryDoesNotExistsException(event.categoryName()));

        if (category.getBudgeting() != null) {
            throw new BudgetingAlreadyExistsException(event.categoryName());
        }

        Budgeting budgeting = new Budgeting(event.budget(), event.created(), event.created());
        category.setBudgeting(budgeting);
        add(event);
    }

    public void apply(CashFlowEvent.BudgetingUpdatedEvent event) {
        List<Category> categories = Type.INFLOW.equals(event.categoryType()) ? inflowCategories : outflowCategories;
        Category category = findCategoryByName(event.categoryName(), categories)
                .orElseThrow(() -> new CategoryDoesNotExistsException(event.categoryName()));

        if (category.getBudgeting() == null) {
            throw new BudgetingDoesNotExistsException(event.categoryName());
        }

        Budgeting updatedBudgeting = new Budgeting(
                event.newBudget(),
                category.getBudgeting().created(),
                event.updated()
        );
        category.setBudgeting(updatedBudgeting);
        add(event);
    }

    public void apply(CashFlowEvent.BudgetingRemovedEvent event) {
        List<Category> categories = Type.INFLOW.equals(event.categoryType()) ? inflowCategories : outflowCategories;
        Category category = findCategoryByName(event.categoryName(), categories)
                .orElseThrow(() -> new CategoryDoesNotExistsException(event.categoryName()));

        if (category.getBudgeting() == null) {
            throw new BudgetingDoesNotExistsException(event.categoryName());
        }

        category.setBudgeting(null);
        add(event);
    }

    private Optional<Category> findCategoryByName(CategoryName categoryName, List<Category> categories) {
        Stack<Category> stack = new Stack<>();
        categories.forEach(stack::push);
        while (!stack.isEmpty()) {
            Category takenCategory = stack.pop();
            boolean isMatched = categoryName.equals(takenCategory.getCategoryName());

            if (isMatched) {
                return Optional.of(takenCategory);
            }
            takenCategory.getSubCategories().forEach(stack::push);
        }
        return Optional.empty();
    }

    private Optional<CashChange> fetchCashChange(CashChangeId cashChangeId) {
        return Optional.ofNullable(cashChanges.getOrDefault(cashChangeId, null));
    }

    private void performOn(CashChangeId cashChangeId, Consumer<CashChange> operation) {
        CashChange cashChange = fetchCashChange(cashChangeId)
                .orElseThrow(() -> new CashChangeDoesNotExistsException(cashChangeId));
        operation.accept(cashChange);
        cashChanges.replace(cashChangeId, cashChange);
    }

    private void add(CashFlowEvent event) {
        // update lastModification timestamp
        this.lastModification = event.occurredAt();
        // update checksum for synchronization with read models
        this.lastMessageChecksum = calculateChecksum(event);
        // store event temporary
        getUncommittedEvents().add(event);
    }

    private Checksum calculateChecksum(CashFlowEvent event) {
        String jsonizedEvent = JsonContent.asJson(event).content();
        return new Checksum(DigestUtils.md5DigestAsHex(jsonizedEvent.getBytes(StandardCharsets.UTF_8)));
    }

    public List<CashFlowEvent> getUncommittedEvents() {
        if (isNull(uncommittedEvents)) {
            uncommittedEvents = new LinkedList<>();
        }
        return uncommittedEvents;
    }

    public enum CashFlowStatus {
        SETUP, OPEN, CLOSED
    }
}
