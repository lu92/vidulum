package com.multi.vidulum.cashflow.app.commands.append;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Slf4j
@Component
@AllArgsConstructor
public class AppendCashChangeCommandHandler implements CommandHandler<AppendCashChangeCommand, CashChangeId> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final Clock clock;

    @Override
    public CashChangeId handle(AppendCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        cashFlow.appendCashChange(
                command.cashChangeId(),
                command.name(),
                command.description(),
                command.money(),
                command.type(),
                ZonedDateTime.now(clock),
                command.dueDate()
        );

        domainCashFlowRepository.save(cashFlow);

        log.info("Cash change [{}] has been appended!", cashFlow.getSnapshot());
        return command.cashChangeId();
    }
}
