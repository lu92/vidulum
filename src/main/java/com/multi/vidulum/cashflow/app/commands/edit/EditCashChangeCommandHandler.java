package com.multi.vidulum.cashflow.app.commands.edit;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@AllArgsConstructor
public class EditCashChangeCommandHandler implements CommandHandler<EditCashChangeCommand, Void> {


    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;

    @Override
    public Void handle(EditCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowEvent.CashChangeEditedEvent event = new CashFlowEvent.CashChangeEditedEvent(
                command.cashFlowId(),
                command.cashChangeId(),
                command.name(),
                command.description(),
                command.money(),
                command.dueDate()
        );

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.CashChangeEditedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );
        log.info("Cash change [{}] has been edited!", command.cashChangeId());
        return null;
    }
}
