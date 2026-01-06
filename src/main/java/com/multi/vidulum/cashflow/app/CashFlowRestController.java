package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.app.commands.activate.ActivateCashFlowCommand;
import com.multi.vidulum.cashflow.app.commands.append.AppendExpectedCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.append.AppendPaidCashChangeCommand;
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
     * Activate a CashFlow, transitioning from SETUP to OPEN mode.
     * This marks the end of the historical import process.
     * All IMPORT_PENDING months will be changed to IMPORTED.
     */
    @PostMapping("/{cashFlowId}/activate")
    public CashFlowDto.ActivateCashFlowResponseJson activateCashFlow(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody CashFlowDto.ActivateCashFlowJson request) {

        // Get CashFlow before activation to calculate the balance
        CashFlow cashFlowBeforeActivation = domainCashFlowRepository.findById(new CashFlowId(cashFlowId))
                .orElseThrow(() -> new CashFlowDoesNotExistsException(new CashFlowId(cashFlowId)));
        Money calculatedBalance = cashFlowBeforeActivation.calculateCurrentBalance();

        CashFlowSnapshot snapshot = commandGateway.send(
                new ActivateCashFlowCommand(
                        new CashFlowId(cashFlowId),
                        request.getConfirmedBalance(),
                        request.isForceActivation()
                )
        );

        Money confirmedBalance = request.getConfirmedBalance();
        Money difference = confirmedBalance.minus(calculatedBalance);
        boolean isZeroDifference = difference.getAmount().compareTo(java.math.BigDecimal.ZERO) == 0;

        return CashFlowDto.ActivateCashFlowResponseJson.builder()
                .cashFlowId(snapshot.cashFlowId().id())
                .confirmedBalance(confirmedBalance)
                .calculatedBalance(calculatedBalance)
                .difference(difference)
                .forced(request.isForceActivation() && !isZeroDifference)
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
    public void confirm(@RequestBody CashFlowDto.ConfirmCashChangeJson request) {
        commandGateway.send(
                new ConfirmCashChangeCommand(
                        new CashFlowId(request.getCashFlowId()),
                        new CashChangeId(request.getCashChangeId()),
                        ZonedDateTime.now(clock)));
    }

    @PostMapping("/edit")
    public void edit(@RequestBody CashFlowDto.EditCashChangeJson request) {
        commandGateway.send(
                new EditCashChangeCommand(
                        new CashFlowId(request.getCashFlowId()),
                        new CashChangeId(request.getCashChangeId()),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.getMoney(),
                        request.getDueDate()
                )
        );
    }

    @PostMapping("/reject")
    public void reject(@RequestBody CashFlowDto.RejectCashChangeJson request) {
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

    @PostMapping("/{cashFlowId}/category")
    public void createCategory(@PathVariable("cashFlowId") String cashFlowId, @RequestBody CashFlowDto.CreateCategoryJson request) {
        commandGateway.send(
                new CreateCategoryCommand(
                        new CashFlowId(cashFlowId),
                        ofNullable(request.getParentCategoryName()).map(CategoryName::new).orElse(CategoryName.NOT_DEFINED),
                        new CategoryName(request.getCategory()),
                        request.getType()
                )
        );
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

}
