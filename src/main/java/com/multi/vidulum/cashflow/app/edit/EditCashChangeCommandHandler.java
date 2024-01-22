package com.multi.vidulum.cashflow.app.edit;

import com.multi.vidulum.cashflow.domain.CashChange;
import com.multi.vidulum.cashflow.domain.CashChangeDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.DomainCashChangeRepository;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class EditCashChangeCommandHandler implements CommandHandler<EditCashChangeCommand, Void> {

    private DomainCashChangeRepository domainCashChangeRepository;

    @Override
    public Void handle(EditCashChangeCommand command) {
        CashChange cashChange = domainCashChangeRepository.findById(command.cashChangeId())
                .orElseThrow(() -> new CashChangeDoesNotExistsException(command.cashChangeId()));

        cashChange.edit(
                command.name(),
                command.description(),
                command.money(),
                command.dueDate()
        );

        CashChange editedCashChange = domainCashChangeRepository.save(cashChange);
        log.info("Cash change [{}] has been edited!", editedCashChange.getSnapshot().cashChangeId());
        return null;
    }
}
