package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.app.commands.confirm.ConfirmCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.create.CreateCashChangeCommand;
import com.multi.vidulum.cashflow.app.commands.edit.EditCashChangeCommand;
import com.multi.vidulum.cashflow.app.queries.GetCashChangeQuery;
import com.multi.vidulum.cashflow.domain.CashChange;
import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.ZonedDateTime;

@AllArgsConstructor
@RestController("/cash-change")
public class CashChangeRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final CashChangeSummaryMapper mapper;
    private final Clock clock;

    @PostMapping
    public CashChangeDto.CashChangeSummaryJson create(@RequestBody CashChangeDto.CreateEmptyCashChangeJson request) {
        CashChange cashChange = commandGateway.send(
                new CreateCashChangeCommand(
                        UserId.of(request.getUserId()),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.getMoney(),
                        request.getType(),
                        request.getDueDate()
                )
        );
        return mapper.map(cashChange.getSnapshot());
    }

    @PostMapping("/confirm")
    public void confirm(@RequestBody CashChangeDto.ConfirmCashChangeJson request) {
        commandGateway.send(
                new ConfirmCashChangeCommand(
                        new CashChangeId(request.getCashChangeId()),
                        ZonedDateTime.now(clock)));
    }

    @PostMapping("/edit")
    public void edit(@RequestBody CashChangeDto.EditCashChangeJson request) {
        commandGateway.send(
                new EditCashChangeCommand(
                        new CashChangeId(request.getCashChangeId()),
                        new Name(request.getName()),
                        new Description(request.getDescription()),
                        request.getMoney(),
                        request.getDueDate()
                )
        );
    }

    @GetMapping("/{cash-change-id}")
    public CashChangeDto.CashChangeSummaryJson getCashChange(@PathVariable("cash-change-id") String cashChangeId) {
        CashChangeSnapshot cashChangeSnapshot = queryGateway.send(
                new GetCashChangeQuery(new CashChangeId(cashChangeId))
        );

        return mapper.map(cashChangeSnapshot);
    }

}
