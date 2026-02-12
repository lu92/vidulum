package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.app.commands.archive.ArchiveCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.archive.UnarchiveCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.attesthistoricalimport.AttestHistoricalImportCommand;
import com.multi.vidulum.cashflow.app.commands.append.AppendExpectedCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.append.AppendPaidCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.rollbackimport.RollbackImportCommand;
import com.multi.vidulum.cashflow.app.commands.rollover.RolloverMonthCommand;
import com.multi.vidulum.cashflow.app.commands.rollover.RolloverMonthResult;
import com.multi.vidulum.cashflow.app.commands.budgeting.remove.RemoveBudgetingCommand;
import com.multi.vidulum.cashflow.app.commands.budgeting.set.SetBudgetingCommand;
import com.multi.vidulum.cashflow.app.commands.budgeting.update.UpdateBudgetingCommand;
import com.multi.vidulum.cashflow.app.commands.comment.create.CreateCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.confirm.ConfirmCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.create.CreateCashFlowCommand;
import com.multi.vidulum.cashflow.app.commands.create.CreateCashFlowWithHistoryCommand;
import com.multi.vidulum.cashflow.app.commands.edit.EditCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.importhistorical.ImportHistoricalCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.reject.RejectCashChangeCommand;
import com.multi.vidulum.cashflow.app.queries.GetCashFlowQuery;
import com.multi.vidulum.cashflow.app.queries.GetDetailsOfCashFlowViaUserQuery;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.BusinessIdGenerator;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Reason;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;

import static java.util.Optional.ofNullable;

@AllArgsConstructor
@RestController
@RequestMapping("/cash-flow")
public class CashFlowRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowSummaryMapper mapper;
    private final BusinessIdGenerator businessIdGenerator;
    private final Clock clock;

    @PostMapping
    public String createCashFlow(@Valid @RequestBody CashFlowDto.CreateCashFlowJson request) {
        CashFlowSnapshot snapshot = commandGateway.send(
                new CreateCashFlowCommand(
                        UserId.of(request.getUserId()),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.toBankAccount()
                )
        );

        return snapshot.cashFlowId().id();
    }

    /**
     * Create a CashFlow with historical data support.
     * The CashFlow will be created in SETUP mode, allowing import of historical transactions.
     */
    @PostMapping("/with-history")
    public String createCashFlowWithHistory(@Valid @RequestBody CashFlowDto.CreateCashFlowWithHistoryJson request) {

        CashFlowSnapshot snapshot = commandGateway.send(
                new CreateCashFlowWithHistoryCommand(
                        UserId.of(request.getUserId()),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.toBankAccount(),
                        YearMonth.parse(request.getStartPeriod()),
                        request.toInitialBalance()
                )
        );

        return snapshot.cashFlowId().id();
    }

    /**
     * Import a historical cash change to a CashFlow in SETUP mode.
     * The transaction will be added to the appropriate historical month based on paidDate.
     */
    @PostMapping("/cf={cashFlowId}/import-historical")
    public String importHistoricalCashChange(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody CashFlowDto.ImportHistoricalCashChangeJson request) {
        CashChangeId cashChangeId = commandGateway.send(
                new ImportHistoricalCashChangeCommand(
                        CashFlowId.of(cashFlowId),
                        new CategoryName(request.getCategory()),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.getMoney(),
                        request.getType(),
                        request.getDueDate(),
                        request.getPaidDate()
                )
        );
        return cashChangeId.id();
    }

    /**
     * Attest a historical import, transitioning CashFlow from SETUP to OPEN mode.
     * This marks the end of the historical import process.
     * All IMPORT_PENDING months will be changed to IMPORTED.
     */
    @PostMapping("/cf={cashFlowId}/attest-historical-import")
    public CashFlowDto.AttestHistoricalImportResponseJson attestHistoricalImport(
            @PathVariable("cashFlowId") String cashFlowId,
            @Valid @RequestBody CashFlowDto.AttestHistoricalImportJson request) {

        // Get CashFlow before attestation to calculate the balance
        CashFlowId cfId = CashFlowId.of(cashFlowId);
        CashFlow cashFlowBeforeAttestation = domainCashFlowRepository.findById(cfId)
                .orElseThrow(() -> new CashFlowDoesNotExistsException(cfId));
        Money calculatedBalance = cashFlowBeforeAttestation.calculateCurrentBalance();

        CashFlowSnapshot snapshot = commandGateway.send(
                new AttestHistoricalImportCommand(
                        cfId,
                        request.getConfirmedBalance(),
                        request.isForceAttestation(),
                        request.isCreateAdjustment()
                )
        );

        Money confirmedBalance = request.getConfirmedBalance();
        Money difference = confirmedBalance.minus(calculatedBalance);
        boolean isZeroDifference = difference.getAmount().compareTo(java.math.BigDecimal.ZERO) == 0;
        boolean adjustmentCreated = !isZeroDifference && request.isCreateAdjustment();

        // Find adjustment cash change ID if created
        String adjustmentCashChangeId = null;
        if (adjustmentCreated) {
            // The adjustment is the last added cash change with name "Balance Adjustment"
            adjustmentCashChangeId = snapshot.cashChanges().values().stream()
                    .filter(cc -> "Balance Adjustment".equals(cc.name().name()))
                    .map(cc -> cc.cashChangeId().id())
                    .findFirst()
                    .orElse(null);
        }

        return CashFlowDto.AttestHistoricalImportResponseJson.builder()
                .cashFlowId(snapshot.cashFlowId().id())
                .confirmedBalance(confirmedBalance)
                .calculatedBalance(calculatedBalance)
                .difference(difference)
                .forced(request.isForceAttestation() && !isZeroDifference && !request.isCreateAdjustment())
                .adjustmentCreated(adjustmentCreated)
                .adjustmentCashChangeId(adjustmentCashChangeId)
                .status(snapshot.status())
                .build();
    }

    /**
     * Rollback (clear) all imported historical data from a CashFlow in SETUP mode.
     * This allows the user to start the import process fresh if mistakes were made.
     * The CashFlow remains in SETUP mode after rollback.
     */
    @DeleteMapping("/cf={cashFlowId}/import")
    public CashFlowDto.RollbackImportResponseJson rollbackImport(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody(required = false) CashFlowDto.RollbackImportJson request) {

        boolean deleteCategories = request != null && request.isDeleteCategories();

        CashFlowSnapshot snapshot = commandGateway.send(
                new RollbackImportCommand(
                        CashFlowId.of(cashFlowId),
                        deleteCategories
                )
        );

        // Get counts from before rollback for response (they're stored in event)
        // For simplicity, we return the current state after rollback
        return CashFlowDto.RollbackImportResponseJson.builder()
                .cashFlowId(snapshot.cashFlowId().id())
                .deletedTransactionsCount(0) // Actual count is logged, response shows post-rollback state
                .deletedCategoriesCount(0)
                .categoriesDeleted(deleteCategories)
                .status(snapshot.status())
                .build();
    }

    @PostMapping("/expected-cash-change")
    public String appendExpectedCashChange(@RequestBody CashFlowDto.AppendExpectedCashChangeJson request) {
        CashChangeId cashChangeId = commandGateway.send(
                new AppendExpectedCashChangeCommand(
                        CashFlowId.of(request.getCashFlowId()),
                        new CategoryName(request.getCategory()),
                        businessIdGenerator.generateCashChangeId(),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.getMoney(),
                        request.getType(),
                        ZonedDateTime.now(clock),
                        request.getDueDate()
                )
        );
        return cashChangeId.id();
    }

    @PostMapping("/paid-cash-change")
    public String appendPaidCashChange(@RequestBody CashFlowDto.AppendPaidCashChangeJson request) {
        CashChangeId cashChangeId = commandGateway.send(
                new AppendPaidCashChangeCommand(
                        CashFlowId.of(request.getCashFlowId()),
                        new CategoryName(request.getCategory()),
                        businessIdGenerator.generateCashChangeId(),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.getMoney(),
                        request.getType(),
                        ZonedDateTime.now(clock),
                        request.getDueDate(),
                        request.getPaidDate()
                )
        );
        return cashChangeId.id();
    }

    @PostMapping("/confirm")
    public void confirm(@Valid @RequestBody CashFlowDto.ConfirmCashChangeJson request) {
        commandGateway.send(
                new ConfirmCashChangeCommand(
                        CashFlowId.of(request.getCashFlowId()),
                        CashChangeId.of(request.getCashChangeId()),
                        ZonedDateTime.now(clock)));
    }

    @PostMapping("/edit")
    public void edit(@Valid @RequestBody CashFlowDto.EditCashChangeJson request) {
        commandGateway.send(
                new EditCashChangeCommand(
                        CashFlowId.of(request.getCashFlowId()),
                        CashChangeId.of(request.getCashChangeId()),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.getMoney(),
                        new CategoryName(request.getCategory()),
                        request.getDueDate()
                )
        );
    }

    @PostMapping("/reject")
    public void reject(@Valid @RequestBody CashFlowDto.RejectCashChangeJson request) {
        commandGateway.send(
                new RejectCashChangeCommand(
                        CashFlowId.of(request.getCashFlowId()),
                        CashChangeId.of(request.getCashChangeId()),
                        new Reason(request.getReason())
                )
        );
    }

    @GetMapping("/cf={cashFlowId}")
    public CashFlowDto.CashFlowSummaryJson getCashFlow(@PathVariable("cashFlowId") String cashFlowId) {
        CashFlowSnapshot snapshot = queryGateway.send(
                new GetCashFlowQuery(CashFlowId.of(cashFlowId))
        );

        return mapper.mapCashFlow(snapshot);
    }

    @GetMapping
    public List<CashFlowDto.CashFlowDetailJson> getCashFlows(@RequestParam("owner") String ownerUsername) {
        List<CashFlowSnapshot> snapshots = queryGateway.send(
                new GetDetailsOfCashFlowViaUserQuery(UserId.of(ownerUsername))
        );

        return snapshots.stream()
                .map(mapper::mapCashFlowDetails)
                .toList();
    }

    /**
     * REST endpoint for category creation.
     * Use isImport=true query parameter when creating categories during bank data import (allowed in SETUP mode).
     */
    @PostMapping("/cf={cashFlowId}/category")
    public void createCategoryEndpoint(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody CashFlowDto.CreateCategoryJson request,
            @RequestParam(name = "isImport", defaultValue = "false") boolean isImport) {
        createCategory(cashFlowId, request, isImport);
    }

    /**
     * Internal method for category creation. Called by REST endpoint and directly by tests.
     * In SETUP mode, only import operations (isImport=true) can create categories.
     */
    public void createCategory(String cashFlowId, CashFlowDto.CreateCategoryJson request, boolean isImport) {
        CashFlowId cfId = CashFlowId.of(cashFlowId);
        CreateCategoryCommand command = isImport
                ? CreateCategoryCommand.forImport(
                        cfId,
                        ofNullable(request.getParentCategoryName()).map(CategoryName::new).orElse(CategoryName.NOT_DEFINED),
                        new CategoryName(request.getCategory()),
                        request.getType())
                : new CreateCategoryCommand(
                        cfId,
                        ofNullable(request.getParentCategoryName()).map(CategoryName::new).orElse(CategoryName.NOT_DEFINED),
                        new CategoryName(request.getCategory()),
                        request.getType());
        commandGateway.send(command);
    }

    /**
     * Convenience method for tests - uses isImport=false (user-initiated category creation).
     * Will throw OperationNotAllowedInSetupModeException if CashFlow is in SETUP mode.
     */
    public void createCategory(String cashFlowId, CashFlowDto.CreateCategoryJson request) {
        createCategory(cashFlowId, request, false);
    }

    @PostMapping("/budgeting")
    public void setBudgeting(@RequestBody CashFlowDto.SetBudgetingJson request) {
        commandGateway.send(
                new SetBudgetingCommand(
                        CashFlowId.of(request.getCashFlowId()),
                        new CategoryName(request.getCategoryName()),
                        request.getCategoryType(),
                        request.getBudget()
                )
        );
    }

    @PutMapping("/budgeting")
    public void updateBudgeting(@RequestBody CashFlowDto.UpdateBudgetingJson request) {
        commandGateway.send(
                new UpdateBudgetingCommand(
                        CashFlowId.of(request.getCashFlowId()),
                        new CategoryName(request.getCategoryName()),
                        request.getCategoryType(),
                        request.getNewBudget()
                )
        );
    }

    @DeleteMapping("/budgeting")
    public void removeBudgeting(@RequestBody CashFlowDto.RemoveBudgetingJson request) {
        commandGateway.send(
                new RemoveBudgetingCommand(
                        CashFlowId.of(request.getCashFlowId()),
                        new CategoryName(request.getCategoryName()),
                        request.getCategoryType()
                )
        );
    }

    /**
     * Archive a category, hiding it from new transaction creation.
     * Archived categories remain visible in historical transactions that used them.
     */
    @PostMapping("/cf={cashFlowId}/category/archive")
    public void archiveCategory(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody CashFlowDto.ArchiveCategoryJson request) {
        commandGateway.send(
                new ArchiveCategoryCommand(
                        CashFlowId.of(cashFlowId),
                        new CategoryName(request.getCategoryName()),
                        request.getCategoryType(),
                        request.isForceArchiveChildren()
                )
        );
    }

    /**
     * Unarchive a category, making it available for new transaction creation again.
     */
    @PostMapping("/cf={cashFlowId}/category/unarchive")
    public void unarchiveCategory(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody CashFlowDto.UnarchiveCategoryJson request) {
        commandGateway.send(
                new UnarchiveCategoryCommand(
                        CashFlowId.of(cashFlowId),
                        new CategoryName(request.getCategoryName()),
                        request.getCategoryType()
                )
        );
    }

    /**
     * Manually trigger a month rollover.
     * <p>
     * This endpoint transitions the current ACTIVE month to ROLLED_OVER status
     * and the next FORECASTED month becomes ACTIVE.
     * <p>
     * Use cases:
     * <ul>
     *   <li>Testing - verify rollover behavior before scheduled job runs</li>
     *   <li>Catch-up - if scheduled job was missed, manually trigger rollover</li>
     *   <li>Power users - explicit control over month transitions</li>
     * </ul>
     * <p>
     * The closing balance is automatically calculated from the current bank account balance.
     * ROLLED_OVER months allow gap filling - importing missed transactions from bank statements.
     *
     * @param cashFlowId the CashFlow to rollover
     * @return rollover result with old and new active periods
     */
    @PostMapping("/cf={cashFlowId}/rollover")
    public CashFlowDto.RolloverMonthResponseJson rolloverMonth(
            @PathVariable("cashFlowId") String cashFlowId) {

        RolloverMonthResult result = commandGateway.send(
                new RolloverMonthCommand(
                        CashFlowId.of(cashFlowId),
                        ZonedDateTime.now(clock)
                )
        );

        return CashFlowDto.RolloverMonthResponseJson.builder()
                .cashFlowId(result.cashFlowId().id())
                .rolledOverPeriod(result.rolledOverPeriod())
                .newActivePeriod(result.newActivePeriod())
                .closingBalance(result.closingBalance())
                .build();
    }

    /**
     * Manually trigger multiple month rollovers (catch-up).
     * <p>
     * This endpoint is useful when the scheduled job was missed for multiple months.
     * It will rollover all months from the current active period up to (but not including) the target period.
     * <p>
     * Example: If current active period is 2025-10 and target is 2026-02,
     * it will rollover 2025-10, 2025-11, 2025-12, 2026-01, making 2026-02 the new active period.
     *
     * @param cashFlowId   the CashFlow to rollover
     * @param targetPeriod the target period that should become ACTIVE (format: yyyy-MM)
     * @return batch rollover result
     */
    @PostMapping("/cf={cashFlowId}/rollover/to/{targetPeriod}")
    public CashFlowDto.BatchRolloverResponseJson rolloverMonthsTo(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("targetPeriod") String targetPeriod) {

        CashFlowId cfId = CashFlowId.of(cashFlowId);
        YearMonth target = YearMonth.parse(targetPeriod);
        ZonedDateTime now = ZonedDateTime.now(clock);

        // Get current active period
        CashFlow cashFlow = domainCashFlowRepository.findById(cfId)
                .orElseThrow(() -> new CashFlowDoesNotExistsException(cfId));
        YearMonth firstPeriod = cashFlow.getSnapshot().activePeriod();

        if (!target.isAfter(firstPeriod)) {
            throw new IllegalArgumentException(
                    "Target period [" + target + "] must be after current active period [" + firstPeriod + "]");
        }

        int monthsRolledOver = 0;
        YearMonth lastRolledOver = firstPeriod;
        RolloverMonthResult lastResult = null;

        // Rollover each month until we reach the target
        YearMonth current = firstPeriod;
        while (current.isBefore(target)) {
            lastResult = commandGateway.send(
                    new RolloverMonthCommand(cfId, now)
            );
            lastRolledOver = lastResult.rolledOverPeriod();
            monthsRolledOver++;
            current = current.plusMonths(1);
        }

        return CashFlowDto.BatchRolloverResponseJson.builder()
                .cashFlowId(cashFlowId)
                .monthsRolledOver(monthsRolledOver)
                .firstRolledOverPeriod(firstPeriod)
                .lastRolledOverPeriod(lastRolledOver)
                .newActivePeriod(lastResult != null ? lastResult.newActivePeriod() : firstPeriod)
                .closingBalance(lastResult != null ? lastResult.closingBalance() : null)
                .build();
    }

}
