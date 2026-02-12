package com.multi.vidulum.cashflow.app.commands.importhistorical;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.BusinessIdGenerator;
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
public class ImportHistoricalCashChangeCommandHandler implements CommandHandler<ImportHistoricalCashChangeCommand, CashChangeId> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final BusinessIdGenerator businessIdGenerator;
    private final Clock clock;

    @Override
    public CashChangeId handle(ImportHistoricalCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();
        YearMonth targetPeriod = YearMonth.from(command.paidDate());
        YearMonth activePeriod = snapshot.activePeriod();
        YearMonth startPeriod = snapshot.startPeriod();
        ZonedDateTime now = ZonedDateTime.now(clock);

        // Validation based on CashFlow status
        switch (snapshot.status()) {
            case SETUP -> {
                // SETUP mode: historical import before activation
                // paidDate must be in a historical month (before activePeriod)
                if (!targetPeriod.isBefore(activePeriod)) {
                    throw new ImportDateOutsideSetupPeriodException(command.paidDate(), targetPeriod, activePeriod);
                }
            }
            case OPEN -> {
                // OPEN mode: ongoing sync or gap filling
                // Can import to: ACTIVE, ROLLED_OVER, IMPORTED months
                // Cannot import to: FORECASTED months
                if (targetPeriod.isAfter(activePeriod)) {
                    throw new ImportToForecastedMonthNotAllowedException(command.cashFlowId(), targetPeriod, activePeriod);
                }
                // Note: For production, we would check month status from forecast processor
                // Here we allow import to any month that is not in the future (relative to activePeriod)
            }
            case CLOSED -> {
                // CLOSED mode: no imports allowed
                throw new ImportNotAllowedInClosedModeException(command.cashFlowId());
            }
        }

        // Validation: paidDate must be >= startPeriod
        if (targetPeriod.isBefore(startPeriod)) {
            throw new ImportDateBeforeStartPeriodException(command.paidDate(), targetPeriod, startPeriod);
        }

        // Validation: paidDate must not be in the future
        if (command.paidDate().isAfter(now)) {
            throw new ImportDateInFutureException(command.paidDate(), now);
        }
        CashChangeId cashChangeId = businessIdGenerator.generateCashChangeId();

        CashFlowEvent.HistoricalCashChangeImportedEvent event = new CashFlowEvent.HistoricalCashChangeImportedEvent(
                command.cashFlowId(),
                cashChangeId,
                command.name(),
                command.description(),
                command.money(),
                command.type(),
                command.categoryName(),
                command.dueDate(),
                command.paidDate(),
                now
        );

        cashFlow.apply(event);
        domainCashFlowRepository.save(cashFlow);

        log.info("Historical cash change [{}] imported to CashFlow [{}] for period [{}]",
                cashChangeId.id(), command.cashFlowId().id(), targetPeriod);

        // Use emitWithKey to ensure event ordering within the same CashFlow
        // Critical for import: CategoryCreatedEvent must be processed before HistoricalCashChangeImportedEvent
        cashFlowEventEmitter.emitWithKey(
                command.cashFlowId(),
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.HistoricalCashChangeImportedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        return cashChangeId;
    }
}
