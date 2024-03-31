package com.multi.vidulum.cashflow.app.commands.create;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Map;

@Slf4j
@Component
@AllArgsConstructor
public class CreateCashFlowCommandHandler implements CommandHandler<CreateCashFlowCommand, CashFlowSnapshot> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;

    private final Clock clock;

    @Override
    public CashFlowSnapshot handle(CreateCashFlowCommand command) {
        CashFlow cashFlow  = new CashFlow();
        CashFlowEvent.CashFlowCreatedEvent event = new CashFlowEvent.CashFlowCreatedEvent(
                CashFlowId.generate(),
                command.userId(),
                command.name(),
                command.description(),
                command.bankAccount(),
                CategoryId.generate(),
                CategoryId.generate(),
                ZonedDateTime.now(clock)
        );
        cashFlow.apply(event);

        CashFlow savedCashFlow = domainCashFlowRepository.save(cashFlow);
        log.info("Cash flow [{}] has been created!", savedCashFlow.getSnapshot());
        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.CashFlowCreatedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );
        return savedCashFlow.getSnapshot();
    }
}
