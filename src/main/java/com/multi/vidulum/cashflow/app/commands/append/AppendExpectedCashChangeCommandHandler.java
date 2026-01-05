package com.multi.vidulum.cashflow.app.commands.append;

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
public class AppendExpectedCashChangeCommandHandler implements CommandHandler<AppendExpectedCashChangeCommand, CashChangeId> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;

    @Override
    public CashChangeId handle(AppendExpectedCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowEvent.ExpectedCashChangeAppendedEvent event = new CashFlowEvent.ExpectedCashChangeAppendedEvent(
                command.cashFlowId(),
                command.cashChangeId(),
                command.name(),
                command.description(),
                command.money(),
                command.type(),
                command.created(),
                command.categoryName(),
                command.dueDate()
        );
        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.ExpectedCashChangeAppendedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Expected cash change [{}] has been appended!", cashFlow.getSnapshot());
        return command.cashChangeId();
    }
}
