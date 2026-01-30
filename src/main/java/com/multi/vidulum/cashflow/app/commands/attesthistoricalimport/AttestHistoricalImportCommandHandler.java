package com.multi.vidulum.cashflow.app.commands.attesthistoricalimport;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.JsonContent;
import com.multi.vidulum.common.Money;
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
public class AttestHistoricalImportCommandHandler implements CommandHandler<AttestHistoricalImportCommand, CashFlowSnapshot> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public CashFlowSnapshot handle(AttestHistoricalImportCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        // Validation 1: CashFlow must be in SETUP mode
        if (snapshot.status() != CashFlow.CashFlowStatus.SETUP) {
            throw new AttestationNotAllowedInNonSetupModeException(command.cashFlowId(), snapshot.status());
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        // Calculate current balance from initial + all imports
        Money calculatedBalance = cashFlow.calculateCurrentBalance();
        Money confirmedBalance = command.confirmedBalance();

        // Calculate difference
        Money difference = confirmedBalance.minus(calculatedBalance);
        boolean isZeroDifference = difference.getAmount().compareTo(java.math.BigDecimal.ZERO) == 0;

        // Validation 2: If balance mismatch and neither forced nor createAdjustment, throw exception
        if (!isZeroDifference && !command.forceAttestation() && !command.createAdjustment()) {
            throw new BalanceMismatchException(
                    command.cashFlowId(),
                    snapshot.name(),
                    confirmedBalance,
                    calculatedBalance,
                    difference
            );
        }

        // Determine if we should create an adjustment transaction
        CashChangeId adjustmentCashChangeId = null;
        if (!isZeroDifference && command.createAdjustment()) {
            adjustmentCashChangeId = CashChangeId.generate();
        }

        CashFlowEvent.HistoricalImportAttestedEvent event = new CashFlowEvent.HistoricalImportAttestedEvent(
                command.cashFlowId(),
                confirmedBalance,
                calculatedBalance,
                difference,
                command.forceAttestation() && !isZeroDifference && !command.createAdjustment(),
                adjustmentCashChangeId,
                now
        );

        cashFlow.apply(event);
        domainCashFlowRepository.save(cashFlow);

        log.info("Historical import attested for CashFlow [{}]. Confirmed balance: [{}], Calculated balance: [{}], " +
                        "Difference: [{}], Forced: [{}], AdjustmentCreated: [{}]",
                command.cashFlowId().id(), confirmedBalance, calculatedBalance, difference,
                event.forced(), adjustmentCashChangeId != null);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        return cashFlow.getSnapshot();
    }
}
