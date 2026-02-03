package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.app.commands.archive.ArchiveCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.archive.UnarchiveCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.attesthistoricalimport.AttestHistoricalImportCommand;
import com.multi.vidulum.cashflow.app.commands.append.AppendExpectedCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.append.AppendPaidCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.rollbackimport.RollbackImportCommand;
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
    private final Clock clock;

    @PostMapping
    public String createCashFlow(@RequestBody CashFlowDto.CreateCashFlowJson request) {
        CashFlowSnapshot snapshot = commandGateway.send(
                new CreateCashFlowCommand(
                        new UserId(request.getUserId()),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.getBankAccount()
                )
        );

        return snapshot.cashFlowId().id();
    }

    /**
     * Create a CashFlow with historical data support.
     * The CashFlow will be created in SETUP mode, allowing import of historical transactions.
     */
    @PostMapping("/with-history")
    public String createCashFlowWithHistory(@RequestBody CashFlowDto.CreateCashFlowWithHistoryJson request) {
        // Validate bankAccountNumber - required for currency determination in forecast processor
        validateBankAccountNumber(request.getBankAccount());

        CashFlowSnapshot snapshot = commandGateway.send(
                new CreateCashFlowWithHistoryCommand(
                        new UserId(request.getUserId()),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.getBankAccount(),
                        YearMonth.parse(request.getStartPeriod()),
                        request.getInitialBalance()
                )
        );

        return snapshot.cashFlowId().id();
    }

    private void validateBankAccountNumber(BankAccount bankAccount) {
        if (bankAccount == null) {
            throw new IllegalArgumentException("bankAccount is required");
        }
        if (bankAccount.bankAccountNumber() == null) {
            throw new IllegalArgumentException("bankAccount.bankAccountNumber is required");
        }
        if (bankAccount.bankAccountNumber().account() == null || bankAccount.bankAccountNumber().account().isBlank()) {
            throw new IllegalArgumentException("bankAccount.bankAccountNumber.account is required");
        }
        if (bankAccount.bankAccountNumber().denomination() == null) {
            throw new IllegalArgumentException("bankAccount.bankAccountNumber.denomination is required");
        }
    }

    /**
     * Import a historical cash change to a CashFlow in SETUP mode.
     * The transaction will be added to the appropriate historical month based on paidDate.
     */
    @PostMapping("/{cashFlowId}/import-historical")
    public String importHistoricalCashChange(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody CashFlowDto.ImportHistoricalCashChangeJson request) {
        CashChangeId cashChangeId = commandGateway.send(
                new ImportHistoricalCashChangeCommand(
                        new CashFlowId(cashFlowId),
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
    @PostMapping("/{cashFlowId}/attest-historical-import")
    public CashFlowDto.AttestHistoricalImportResponseJson attestHistoricalImport(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody CashFlowDto.AttestHistoricalImportJson request) {

        // Get CashFlow before attestation to calculate the balance
        CashFlow cashFlowBeforeAttestation = domainCashFlowRepository.findById(new CashFlowId(cashFlowId))
                .orElseThrow(() -> new CashFlowDoesNotExistsException(new CashFlowId(cashFlowId)));
        Money calculatedBalance = cashFlowBeforeAttestation.calculateCurrentBalance();

        CashFlowSnapshot snapshot = commandGateway.send(
                new AttestHistoricalImportCommand(
                        new CashFlowId(cashFlowId),
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
    @DeleteMapping("/{cashFlowId}/import")
    public CashFlowDto.RollbackImportResponseJson rollbackImport(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody(required = false) CashFlowDto.RollbackImportJson request) {

        boolean deleteCategories = request != null && request.isDeleteCategories();

        CashFlowSnapshot snapshot = commandGateway.send(
                new RollbackImportCommand(
                        new CashFlowId(cashFlowId),
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
                        new CashFlowId(request.getCashFlowId()),
                        new CategoryName(request.getCategory()),
                        new CashChangeId(CashChangeId.generate().id()),
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
                        new CashFlowId(request.getCashFlowId()),
                        new CategoryName(request.getCategory()),
                        new CashChangeId(CashChangeId.generate().id()),
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
                        new CashFlowId(request.getCashFlowId()),
                        new CashChangeId(request.getCashChangeId()),
                        ZonedDateTime.now(clock)));
    }

    @PostMapping("/edit")
    public void edit(@Valid @RequestBody CashFlowDto.EditCashChangeJson request) {
        commandGateway.send(
                new EditCashChangeCommand(
                        new CashFlowId(request.getCashFlowId()),
                        new CashChangeId(request.getCashChangeId()),
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
                        new CashFlowId(request.getCashFlowId()),
                        new CashChangeId(request.getCashChangeId()),
                        new Reason(request.getReason())
                )
        );
    }

    @GetMapping("/{cashFlowId}")
    public CashFlowDto.CashFlowSummaryJson getCashFlow(@PathVariable("cashFlowId") String cashFlowId) {
        CashFlowSnapshot snapshot = queryGateway.send(
                new GetCashFlowQuery(new CashFlowId(cashFlowId))
        );

        return mapper.mapCashFlow(snapshot);
    }

    @GetMapping("/viaUser/{userId}")
    public List<CashFlowDto.CashFlowDetailJson> getDetailsOfCashFlowViaUser(@PathVariable("userId") String userId) {
        List<CashFlowSnapshot> snapshots = queryGateway.send(
                new GetDetailsOfCashFlowViaUserQuery(new UserId(userId))
        );

        return snapshots.stream()
                .map(mapper::mapCashFlowDetails)
                .toList();
    }

    /**
     * REST endpoint for category creation.
     * Use isImport=true query parameter when creating categories during bank data import (allowed in SETUP mode).
     */
    @PostMapping("/{cashFlowId}/category")
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
        CreateCategoryCommand command = isImport
                ? CreateCategoryCommand.forImport(
                        new CashFlowId(cashFlowId),
                        ofNullable(request.getParentCategoryName()).map(CategoryName::new).orElse(CategoryName.NOT_DEFINED),
                        new CategoryName(request.getCategory()),
                        request.getType())
                : new CreateCategoryCommand(
                        new CashFlowId(cashFlowId),
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
                        new CashFlowId(request.getCashFlowId()),
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
                        new CashFlowId(request.getCashFlowId()),
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
                        new CashFlowId(request.getCashFlowId()),
                        new CategoryName(request.getCategoryName()),
                        request.getCategoryType()
                )
        );
    }

    /**
     * Archive a category, hiding it from new transaction creation.
     * Archived categories remain visible in historical transactions that used them.
     */
    @PostMapping("/{cashFlowId}/category/archive")
    public void archiveCategory(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody CashFlowDto.ArchiveCategoryJson request) {
        commandGateway.send(
                new ArchiveCategoryCommand(
                        new CashFlowId(cashFlowId),
                        new CategoryName(request.getCategoryName()),
                        request.getCategoryType(),
                        request.isForceArchiveChildren()
                )
        );
    }

    /**
     * Unarchive a category, making it available for new transaction creation again.
     */
    @PostMapping("/{cashFlowId}/category/unarchive")
    public void unarchiveCategory(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody CashFlowDto.UnarchiveCategoryJson request) {
        commandGateway.send(
                new UnarchiveCategoryCommand(
                        new CashFlowId(cashFlowId),
                        new CategoryName(request.getCategoryName()),
                        request.getCategoryType()
                )
        );
    }

}
