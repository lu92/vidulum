package com.multi.vidulum.cashflow.app.commands.importhistorical;

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
public class ImportHistoricalCashChangeCommandHandler implements CommandHandler<ImportHistoricalCashChangeCommand, CashChangeId> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public CashChangeId handle(ImportHistoricalCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        // Validation 1: CashFlow must be in SETUP mode
        if (snapshot.status() != CashFlow.CashFlowStatus.SETUP) {
            throw new ImportNotAllowedInNonSetupModeException(command.cashFlowId(), snapshot.status());
        }

        // Validation 2: paidDate must be in a historical month (before activePeriod)
        YearMonth targetPeriod = YearMonth.from(command.paidDate());
        YearMonth activePeriod = snapshot.activePeriod();
        if (!targetPeriod.isBefore(activePeriod)) {
            throw new ImportDateOutsideSetupPeriodException(command.paidDate(), targetPeriod, activePeriod);
        }

        // Validation 3: paidDate must be >= startPeriod (month must exist in IMPORT_PENDING)
        YearMonth startPeriod = snapshot.startPeriod();
        if (targetPeriod.isBefore(startPeriod)) {
            throw new ImportDateBeforeStartPeriodException(command.paidDate(), targetPeriod, startPeriod);
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        // Validation 4: paidDate must not be in the future (can only import past transactions)
        if (command.paidDate().isAfter(now)) {
            throw new ImportDateInFutureException(command.paidDate(), now);
        }
        CashChangeId cashChangeId = CashChangeId.generate();

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

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.HistoricalCashChangeImportedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        return cashChangeId;
    }
}
