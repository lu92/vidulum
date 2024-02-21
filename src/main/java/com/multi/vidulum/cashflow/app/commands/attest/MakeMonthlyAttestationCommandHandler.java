package com.multi.vidulum.cashflow.app.commands.attest;

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
public class MakeMonthlyAttestationCommandHandler implements CommandHandler<MakeMonthlyAttestationCommand, Void> {
    private DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;

    @Override
    public Void handle(MakeMonthlyAttestationCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowEvent.MonthAttestedEvent event = new CashFlowEvent.MonthAttestedEvent(
                command.cashFlowId(),
                command.period(),
                command.currentMoney(),
                command.dateTime()
        );
        cashFlow.apply(event);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.MonthAttestedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );
        log.info("Cash flow [{}] period [{}] now is active!", command.cashFlowId(), command.period());
        return null;
    }
}
