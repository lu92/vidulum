package com.multi.vidulum.cashflow.app.commands.create;

import com.multi.vidulum.cashflow.domain.CashChange;
import com.multi.vidulum.cashflow.domain.CashChangeFactory;
import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.DomainCashChangeRepository;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Slf4j
@Component
@AllArgsConstructor
public class CreateCashChangeCommandHandler implements CommandHandler<CreateCashChangeCommand, CashChange> {

    private final DomainCashChangeRepository domainCashChangeRepository;
    private final CashChangeFactory cashChangeFactory;
    private final Clock clock;

    @Override
    public CashChange handle(CreateCashChangeCommand command) {
        CashChange cashChange = cashChangeFactory.empty(
                CashChangeId.generate(),
                command.userId(),
                command.name(),
                command.description(),
                command.money(),
                command.type(),
                ZonedDateTime.now(clock),
                command.dueDate()
        );

        CashChange savedCashChange = domainCashChangeRepository.save(cashChange);
        log.info("Cash change [{}] has been created!", cashChange.getSnapshot());
        return savedCashChange;
    }
}
