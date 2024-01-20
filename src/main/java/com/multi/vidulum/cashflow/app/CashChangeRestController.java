package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.app.create.CreateCashChangeCommand;
import com.multi.vidulum.cashflow.domain.CashChange;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class CashChangeRestController {

    private final CommandGateway commandGateway;
    private final CashChangeSummaryMapper mapper;

    @PostMapping
    public CashChangeDto.CashChangeSummaryJson createEmptyCashChange(@RequestBody CashChangeDto.CreateEmptyCashChangeJson request) {
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
}
