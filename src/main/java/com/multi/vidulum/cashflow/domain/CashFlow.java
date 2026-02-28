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

/**
 * CashFlow aggregate - represents a user's cash flow tracking for a bank account.
 * <p>
 * Supports two modes of creation:
 * <ul>
 *   <li><b>Quick Start</b> - creates CashFlow in OPEN status, starting from current month</li>
 *   <li><b>Advanced Setup</b> - creates CashFlow in SETUP status for historical data import</li>
 * </ul>
 * <p>
 * Lifecycle: SETUP → OPEN → CLOSED
 * <p>
 * In SETUP mode, only import operations are allowed. After attestation, status changes to OPEN.
 *
 * @see CashFlowStatus
 * @see CashFlowEvent.HistoricalImportAttestedEvent
 */
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

    /**
     * The first month of the CashFlow (inclusive).
     * <p>
     * For Quick Start: equals activePeriod (current month).
     * For Advanced Setup: can be in the past (e.g., 2 years back) to allow historical import.
     * <p>
     * Historical transactions can only be imported for months between startPeriod and activePeriod.
     */
    private YearMonth startPeriod;

    /**
     * The current active month (the "now" month).
     * <p>
     * All months before activePeriod are historical (IMPORT_PENDING or IMPORTED status in forecast).
     * The activePeriod month has ACTIVE status.
     * All months after activePeriod are future (FORECASTED status).
     */
    private YearMonth activePeriod;

    /**
     * The opening balance at the start of startPeriod.
     * <p>
     * This is the bank account balance at the beginning of the first historical month.
     * Used to calculate expected balance during attestation:
     * calculatedBalance = initialBalance + sum(inflows) - sum(outflows)
     */
    private Money initialBalance;

    private List<Category> inflowCategories;
    private List<Category> outflowCategories;
    private ZonedDateTime created;
    private ZonedDateTime lastModification;

    /**
     * The timestamp marking the boundary between historical imports and new data.
     * <p>
     * Set during attestation (HistoricalImportAttestedEvent) to the attestation timestamp.
     * Before attestation: null.
     * After attestation: the moment when historical import was finalized.
     * <p>
     * This field can be used to:
     * <ul>
     *   <li>Distinguish between imported historical data and manually added transactions</li>
     *   <li>Apply different validation rules for data before/after this cutoff</li>
     *   <li>Prevent modifications to historical data after attestation</li>
     * </ul>
     */
    private ZonedDateTime importCutoffDateTime;

    private Checksum lastMessageChecksum;
    private List<CashFlowEvent> uncommittedEvents = new LinkedList<>();


    @Override
    public CashFlowSnapshot getSnapshot() {
        Map<CashChangeId, CashChangeSnapshot> cashChangeSnapshotMap = cashChanges.values().stream()
                .map(CashChange::getSnapshot)
                .collect(
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
        inflowCategories.add(Category.createUncategorized());
        LinkedList<Category> outflowCategories = new LinkedList<>();
        outflowCategories.add(Category.createUncategorized());

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
        inflowCategories.add(Category.createUncategorized());
        LinkedList<Category> outflowCategories = new LinkedList<>();
        outflowCategories.add(Category.createUncategorized());

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
                null,
                event.sourceRuleId()
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
                event.paidDate(),
                null
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
                event.paidDate(),
                null
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
                    event.attestedAt(),
                    null
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
                    cashChange.setCategoryName(event.categoryName());
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

    /**
     * @deprecated Use {@link #apply(CashFlowEvent.MonthRolledOverEvent)} instead.
     */
    @Deprecated
    public void apply(CashFlowEvent.MonthAttestedEvent event) {
        if (!activePeriod.plusMonths(1).equals(event.period())) {
            throw new IllegalArgumentException("Active period [%s] and requested attestation period: [%s] - invalid period!".formatted(activePeriod, event.period()));
        }

        activePeriod = event.period();
        bankAccount = bankAccount.withUpdatedBalance(event.currentMoney());
        add(event);
    }

    /**
     * Applies a month rollover event, transitioning from one month to the next.
     * <p>
     * This method:
     * <ul>
     *   <li>Updates activePeriod to the new active period</li>
     *   <li>Updates bank account balance to the closing balance</li>
     * </ul>
     * <p>
     * Note: The actual status changes (ACTIVE → ROLLED_OVER, FORECASTED → ACTIVE)
     * are handled by the {@code MonthRolledOverEventHandler} in the forecast processor.
     */
    public void apply(CashFlowEvent.MonthRolledOverEvent event) {
        if (!activePeriod.equals(event.rolledOverPeriod())) {
            throw new IllegalArgumentException(
                    "Active period [%s] does not match rolled over period: [%s]"
                            .formatted(activePeriod, event.rolledOverPeriod()));
        }
        if (!activePeriod.plusMonths(1).equals(event.newActivePeriod())) {
            throw new IllegalArgumentException(
                    "New active period [%s] must be exactly one month after current active period [%s]"
                            .formatted(event.newActivePeriod(), activePeriod));
        }

        activePeriod = event.newActivePeriod();
        bankAccount = bankAccount.withUpdatedBalance(event.closingBalance());
        add(event);
    }

    public void apply(CashFlowEvent.CategoryCreatedEvent event) {
        List<Category> categories = Type.INFLOW.equals(event.type()) ? inflowCategories : outflowCategories;

        Category newCategory = new Category(
                event.categoryName(),
                null,
                new LinkedList<>(),
                true);

        if (event.parentCategoryName() != null && event.parentCategoryName().isDefined()) {
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

    /**
     * Archives a category, setting validTo to the archive timestamp.
     * Archived categories are hidden from new transaction creation.
     * If forceArchiveChildren is true, all subcategories are also archived.
     */
    public void apply(CashFlowEvent.CategoryArchivedEvent event) {
        List<Category> categories = Type.INFLOW.equals(event.categoryType()) ? inflowCategories : outflowCategories;
        Category category = findCategoryByName(event.categoryName(), categories)
                .orElseThrow(() -> new CategoryDoesNotExistsException(event.categoryName()));

        category.archive(event.archivedAt());

        // If forceArchiveChildren is true, archive all subcategories recursively
        if (event.forceArchiveChildren()) {
            archiveSubcategoriesRecursively(category.getSubCategories(), event.archivedAt());
        }

        add(event);
    }

    /**
     * Recursively archives all subcategories.
     */
    private void archiveSubcategoriesRecursively(List<Category> subcategories, ZonedDateTime archivedAt) {
        for (Category subcategory : subcategories) {
            if (!subcategory.isArchived() && subcategory.getOrigin() != CategoryOrigin.SYSTEM) {
                subcategory.archive(archivedAt);
            }
            archiveSubcategoriesRecursively(subcategory.getSubCategories(), archivedAt);
        }
    }

    /**
     * Unarchives a category, clearing the validTo date.
     * The category becomes available for new transaction creation again.
     * <p>
     * <b>Note:</b> Unarchive is primarily intended for accidental archive recovery.
     * If a user archived a category by mistake and there's no new active category with the same name,
     * they can unarchive to restore it. Once a new category with the same name is created,
     * the old archived category cannot be unarchived anymore (validation done in handler).
     */
    public void apply(CashFlowEvent.CategoryUnarchivedEvent event) {
        List<Category> categories = Type.INFLOW.equals(event.categoryType()) ? inflowCategories : outflowCategories;
        Category category = findCategoryByName(event.categoryName(), categories)
                .orElseThrow(() -> new CategoryDoesNotExistsException(event.categoryName()));

        category.unarchive();
        add(event);
    }

    /**
     * Deletes a single PENDING cash change from the CashFlow.
     * Used primarily by Recurring Rules module when deleting individual transactions.
     * <p>
     * Note: Only PENDING (expected) cash changes can be deleted.
     * CONFIRMED transactions are protected and cannot be deleted.
     */
    public void apply(CashFlowEvent.ExpectedCashChangeDeletedEvent event) {
        CashChange cashChange = fetchCashChange(event.cashChangeId())
                .orElseThrow(() -> new CashChangeDoesNotExistsException(event.cashChangeId()));

        cashChange.onlyWhenIsPending(() -> {
            cashChanges.remove(event.cashChangeId());
        });
        add(event);
    }

    /**
     * Deletes multiple PENDING cash changes from the CashFlow in batch.
     * Used primarily by Recurring Rules module when deleting a rule or changing its schedule.
     * <p>
     * Note: Only PENDING (expected) cash changes are deleted.
     * CONFIRMED transactions are skipped silently.
     */
    public void apply(CashFlowEvent.ExpectedCashChangesBatchDeletedEvent event) {
        for (CashChangeId cashChangeId : event.deletedIds()) {
            fetchCashChange(cashChangeId).ifPresent(cashChange -> {
                if (cashChange.getStatus() == CashChangeStatus.PENDING) {
                    cashChanges.remove(cashChangeId);
                }
            });
        }
        add(event);
    }

    /**
     * Updates multiple cash changes in batch.
     * Used primarily by Recurring Rules module when editing rule amount/category.
     * <p>
     * Note: Only PENDING (expected) cash changes are updated.
     * CONFIRMED transactions are skipped silently.
     * <p>
     * Supported changes:
     * <ul>
     *   <li>amount - the new Money amount</li>
     *   <li>name - the new Name</li>
     *   <li>categoryName - the new CategoryName</li>
     * </ul>
     */
    public void apply(CashFlowEvent.CashChangesBatchUpdatedEvent event) {
        for (CashChangeId cashChangeId : event.updatedIds()) {
            fetchCashChange(cashChangeId).ifPresent(cashChange -> {
                if (cashChange.getStatus() == CashChangeStatus.PENDING) {
                    // Apply changes based on what's in the changes map
                    if (event.changes().containsKey("amount")) {
                        cashChange.setMoney((Money) event.changes().get("amount"));
                    }
                    if (event.changes().containsKey("name")) {
                        cashChange.setName((Name) event.changes().get("name"));
                    }
                    if (event.changes().containsKey("categoryName")) {
                        cashChange.setCategoryName((CategoryName) event.changes().get("categoryName"));
                    }
                }
            });
        }
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

    /**
     * Returns a CashChange by ID if it exists and is PENDING.
     * Used for single delete operation validation.
     *
     * @param cashChangeId the ID of the cash change
     * @return Optional containing the PENDING cash change, or empty if not found or not PENDING
     */
    public Optional<CashChange> findPendingCashChange(CashChangeId cashChangeId) {
        return fetchCashChange(cashChangeId)
                .filter(cc -> cc.getStatus() == CashChangeStatus.PENDING);
    }

    /**
     * Returns all PENDING cash changes that were created by a specific recurring rule.
     * Used for batch delete/update operations.
     *
     * @param sourceRuleId the recurring rule ID
     * @param fromDate optional - only include cash changes with dueDate >= fromDate
     * @return list of PENDING cash changes from the specified rule
     */
    public List<CashChange> findPendingCashChangesBySourceRuleId(String sourceRuleId, ZonedDateTime fromDate) {
        return cashChanges.values().stream()
                .filter(cc -> cc.getStatus() == CashChangeStatus.PENDING)
                .filter(cc -> sourceRuleId.equals(cc.getSourceRuleId()))
                .filter(cc -> fromDate == null || !cc.getDueDate().isBefore(fromDate))
                .toList();
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
