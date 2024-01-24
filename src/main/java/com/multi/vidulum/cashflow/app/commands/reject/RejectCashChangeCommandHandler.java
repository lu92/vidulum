package com.multi.vidulum.cashflow.app.commands.reject;

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
public class RejectCashChangeCommandHandler implements CommandHandler<RejectCashChangeCommand, Void> {
    private final DomainCashChangeRepository domainCashChangeRepository;

    @Override
    public Void handle(RejectCashChangeCommand command) {
        CashChange cashChange = domainCashChangeRepository.findById(command.cashChangeId())
                .orElseThrow(() -> new CashChangeDoesNotExistsException(command.cashChangeId()));

        cashChange.reject(command.reason());

        CashChange rejectedCashChange = domainCashChangeRepository.save(cashChange);
        log.info("Cash change [{}] has been rejected!", rejectedCashChange.getSnapshot().cashChangeId());
        return null;
    }
}
