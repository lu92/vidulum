package com.multi.vidulum.cashflow.app.commands.rollover;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.Map;

/**
 * Handles RolloverMonthCommand - transitions the CashFlow from current month to the next.
 * <p>
 * This handler:
 * <ul>
 *   <li>Validates the CashFlow is in OPEN status</li>
 *   <li>Calculates the closing balance from the bank account</li>
 *   <li>Creates and applies MonthRolledOverEvent</li>
 *   <li>Emits the event to Kafka for forecast processor</li>
 * </ul>
 * <p>
 * The closing balance is taken from the bank account's current balance,
 * which is automatically updated as transactions are confirmed.
 */
@Slf4j
@Component
@AllArgsConstructor
public class RolloverMonthCommandHandler implements CommandHandler<RolloverMonthCommand, RolloverMonthResult> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;

    @Override
    public RolloverMonthResult handle(RolloverMonthCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        // Validate CashFlow is in OPEN status
        if (snapshot.status() != CashFlow.CashFlowStatus.OPEN) {
            throw new RolloverNotAllowedException(
                    command.cashFlowId(),
                    "CashFlow must be in OPEN status to perform rollover. Current status: " + snapshot.status());
        }

        YearMonth currentActivePeriod = snapshot.activePeriod();
        YearMonth newActivePeriod = currentActivePeriod.plusMonths(1);
        Money closingBalance = snapshot.bankAccount().balance();

        log.info("Rolling over CashFlow [{}] from period [{}] to [{}] with closing balance [{}]",
                command.cashFlowId().id(), currentActivePeriod, newActivePeriod, closingBalance);

        CashFlowEvent.MonthRolledOverEvent event = new CashFlowEvent.MonthRolledOverEvent(
                command.cashFlowId(),
                currentActivePeriod,
                newActivePeriod,
                closingBalance,
                command.dateTime()
        );

        cashFlow.apply(event);
        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.MonthRolledOverEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("CashFlow [{}] rolled over successfully. New active period: [{}]",
                command.cashFlowId().id(), newActivePeriod);

        return new RolloverMonthResult(
                command.cashFlowId(),
                currentActivePeriod,
                newActivePeriod,
                closingBalance
        );
    }
}
