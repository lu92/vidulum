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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handler for batch deleting PENDING (expected) cash changes.
 * <p>
 * Uses explicit list of cash change IDs provided by caller (typically RecurringRules module)
 * instead of searching by sourceRuleId in database. This avoids race condition issues
 * where sourceRuleId might be lost due to concurrent writes.
 * <p>
 * Validates:
 * - CashFlow exists
 * - CashFlow is not in SETUP mode
 * <p>
 * Returns a response with:
 * - deletedCount: number of PENDING cash changes deleted
 * - skippedCount: number of cash changes skipped (CONFIRMED or not found)
 */
@Slf4j
@Component
@AllArgsConstructor
public class BatchDeleteExpectedCashChangesCommandHandler implements CommandHandler<BatchDeleteExpectedCashChangesCommand, BatchDeleteResult> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public BatchDeleteResult handle(BatchDeleteExpectedCashChangesCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        // Validate: operation not allowed in SETUP mode
        if (CashFlow.CashFlowStatus.SETUP.equals(snapshot.status())) {
            throw new OperationNotAllowedInSetupModeException("batchDeleteExpectedCashChanges", command.cashFlowId());
        }

        if (command.cashChangeIds() == null || command.cashChangeIds().isEmpty()) {
            log.info("No cash change IDs provided for batch delete in CashFlow [{}]", command.cashFlowId());
            return new BatchDeleteResult(0, 0);
        }

        // Filter to only PENDING cash changes that exist
        List<CashChangeId> deletedIds = new ArrayList<>();
        int skippedCount = 0;

        for (CashChangeId cashChangeId : command.cashChangeIds()) {
            CashChangeSnapshot cashChange = snapshot.cashChanges().get(cashChangeId);
            if (cashChange == null) {
                log.debug("Cash change [{}] not found in CashFlow [{}], skipping",
                        cashChangeId, command.cashFlowId());
                skippedCount++;
            } else if (cashChange.status() != CashChangeStatus.PENDING) {
                log.debug("Cash change [{}] is not PENDING (status={}), skipping",
                        cashChangeId, cashChange.status());
                skippedCount++;
            } else {
                deletedIds.add(cashChangeId);
            }
        }

        if (deletedIds.isEmpty()) {
            log.info("No PENDING cash changes to delete for sourceRuleId [{}] in CashFlow [{}]",
                    command.sourceRuleId(), command.cashFlowId());
            return new BatchDeleteResult(0, skippedCount);
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        CashFlowEvent.ExpectedCashChangesBatchDeletedEvent event = new CashFlowEvent.ExpectedCashChangesBatchDeletedEvent(
                command.cashFlowId(),
                command.sourceRuleId(),
                deletedIds,
                now
        );

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.ExpectedCashChangesBatchDeletedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Batch deleted [{}] expected cash changes for sourceRuleId [{}] from CashFlow [{}], skipped [{}]",
                deletedIds.size(), command.sourceRuleId(), command.cashFlowId(), skippedCount);

        return new BatchDeleteResult(deletedIds.size(), skippedCount);
    }
}
