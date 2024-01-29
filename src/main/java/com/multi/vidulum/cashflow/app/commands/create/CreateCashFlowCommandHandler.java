package com.multi.vidulum.cashflow.app.commands.create;

import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedList;

@Slf4j
@Component
@AllArgsConstructor
public class CreateCashFlowCommandHandler implements CommandHandler<CreateCashFlowCommand, CashFlowSnapshot> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final Clock clock;

    @Override
    public CashFlowSnapshot handle(CreateCashFlowCommand command) {
        CashFlowId cashFlowId = CashFlowId.generate();
        CashFlow cashFlow = new CashFlow(
                cashFlowId,
                command.userId(),
                command.name(),
                command.description(),
                command.balance(),
                CashFlow.CashFlowStatus.OPEN,
                new HashMap<>(),
                ZonedDateTime.now(clock),
                null,
                new LinkedList<>()
        );

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        cashFlow.getUncommittedEvents().add(
                new CashFlowEvent.CashFlowCreatedEvent(
                        snapshot.cashFlowId(),
                        snapshot.userId(),
                        snapshot.name(),
                        snapshot.description(),
                        snapshot.balance(),
                        snapshot.created()
                )
        );

        CashFlow savedCashFlow = domainCashFlowRepository.save(cashFlow);
        log.info("Cash flow [{}] has been created!", savedCashFlow.getSnapshot());
        return savedCashFlow.getSnapshot();
    }
}
