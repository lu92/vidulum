package com.multi.vidulum.cashflow.app.commands.reject;

import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class RejectCashChangeCommandHandler implements CommandHandler<RejectCashChangeCommand, Void> {

    private final DomainCashFlowRepository domainCashFlowRepository;

    @Override
    public Void handle(RejectCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        cashFlow.reject(command.cashChangeId(), command.reason());

        domainCashFlowRepository.save(cashFlow);

        log.info("Cash change [{}] has been rejected!", command.cashChangeId());
        return null;
    }
}
