package com.multi.vidulum.cashflow.app.commands.confirm;

import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ConfirmCashChangeCommandHandler implements CommandHandler<ConfirmCashChangeCommand, Void> {

    private final DomainCashFlowRepository domainCashFlowRepository;

    @Override
    public Void handle(ConfirmCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        cashFlow.apply(
                new CashFlowEvent.CashChangeConfirmedEvent(
                        command.cashFlowId(),
                        command.cashChangeId(),
                        command.endDate()));

        domainCashFlowRepository.save(cashFlow);
        log.info("Cash change [{}] has been confirmed!", command.cashChangeId().id());
        return null;
    }
}
