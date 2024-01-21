package com.multi.vidulum.cashflow.app.confirm;

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
public class ConfirmCashChangeCommandHandler implements CommandHandler<ConfirmCashChangeCommand, Void> {

    private final DomainCashChangeRepository domainCashChangeRepository;

    @Override
    public Void handle(ConfirmCashChangeCommand command) {
        CashChange cashChange = domainCashChangeRepository.findById(command.cashChangeId())
                .orElseThrow(() -> new CashChangeDoesNotExistsException(command.cashChangeId()));

        cashChange.confirm(command.endDate());
        domainCashChangeRepository.save(cashChange);
        log.info("Cash change [{}] has been confirmed!", command.cashChangeId().id());
        return null;
    }
}
