package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.app.commands.append.AppendCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.comment.create.CreateCategoryCommand;
import com.multi.vidulum.cashflow.app.commands.confirm.ConfirmCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.create.CreateCashFlowCommand;
import com.multi.vidulum.cashflow.app.commands.edit.EditCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.reject.RejectCashChangeCommand;
import com.multi.vidulum.cashflow.app.queries.GetCashFlowQuery;
import com.multi.vidulum.cashflow.app.queries.GetDetailsOfCashFlowViaUserQuery;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Reason;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

import static java.util.Optional.ofNullable;

@AllArgsConstructor
@RestController
@RequestMapping("/cash-flow")
public class CashFlowRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
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

    @PostMapping("/cash-change")
    public String appendCashChange(@RequestBody CashFlowDto.AppendCashChangeJson request) {
        CashChangeId cashChangeId = commandGateway.send(
                new AppendCashChangeCommand(
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

}
