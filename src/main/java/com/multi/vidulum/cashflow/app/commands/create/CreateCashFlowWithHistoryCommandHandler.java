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
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Map;

@Slf4j
@Component
@AllArgsConstructor
public class CreateCashFlowWithHistoryCommandHandler implements CommandHandler<CreateCashFlowWithHistoryCommand, CashFlowSnapshot> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public CashFlowSnapshot handle(CreateCashFlowWithHistoryCommand command) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        YearMonth activePeriod = YearMonth.from(now);

        // Validate: startPeriod must be before or equal to activePeriod
        if (command.startPeriod().isAfter(activePeriod)) {
            throw new StartPeriodInFutureException(command.startPeriod(), activePeriod);
        }

        CashFlow cashFlow = new CashFlow();
        CashFlowEvent.CashFlowWithHistoryCreatedEvent event = new CashFlowEvent.CashFlowWithHistoryCreatedEvent(
                CashFlowId.generate(),
                command.userId(),
                command.name(),
                command.description(),
                command.bankAccount(),
                command.startPeriod(),
                activePeriod,
                command.initialBalance(),
                now
        );
        cashFlow.apply(event);

        CashFlow savedCashFlow = domainCashFlowRepository.save(cashFlow);
        log.info("Cash flow with history [{}] has been created in SETUP mode!", savedCashFlow.getSnapshot());

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.CashFlowWithHistoryCreatedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        return savedCashFlow.getSnapshot();
    }
}
