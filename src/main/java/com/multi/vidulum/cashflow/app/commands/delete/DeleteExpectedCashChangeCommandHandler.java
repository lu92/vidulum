package com.multi.vidulum.cashflow.app.commands.delete;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
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

/**
 * Handler for deleting a single PENDING (expected) cash change.
 * <p>
 * Validates:
 * - CashFlow exists
 * - CashFlow is not in SETUP mode
 * - CashChange exists
 * - CashChange is PENDING (not CONFIRMED)
 */
@Slf4j
@Component
@AllArgsConstructor
public class DeleteExpectedCashChangeCommandHandler implements CommandHandler<DeleteExpectedCashChangeCommand, Void> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public Void handle(DeleteExpectedCashChangeCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        // Validate: operation not allowed in SETUP mode
        if (CashFlow.CashFlowStatus.SETUP.equals(snapshot.status())) {
            throw new OperationNotAllowedInSetupModeException("deleteExpectedCashChange", command.cashFlowId());
        }

        // Find the CashChange
        CashChangeSnapshot cashChange = snapshot.cashChanges().get(command.cashChangeId());
        if (cashChange == null) {
            throw new CashChangeDoesNotExistsException(command.cashChangeId());
        }

        // Validate: only PENDING cash changes can be deleted
        if (cashChange.status() != CashChangeStatus.PENDING) {
            throw new CashChangeIsNotOpenedException(cashChange.type(), command.cashChangeId());
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        CashFlowEvent.ExpectedCashChangeDeletedEvent event = new CashFlowEvent.ExpectedCashChangeDeletedEvent(
                command.cashFlowId(),
                command.cashChangeId(),
                cashChange.sourceRuleId(),
                cashChange.dueDate(),
                cashChange.money(),
                now
        );

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.ExpectedCashChangeDeletedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Expected cash change [{}] has been deleted from CashFlow [{}]",
                command.cashChangeId(), command.cashFlowId());
        return null;
    }
}
