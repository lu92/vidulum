package com.multi.vidulum.cashflow.app.commands.update;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for batch updating PENDING (expected) cash changes.
 * <p>
 * Uses explicit list of cash change IDs provided by caller (typically RecurringRules module)
 * instead of searching by sourceRuleId in database. This avoids race condition issues
 * where sourceRuleId might be lost due to concurrent writes.
 * <p>
 * Validates:
 * - CashFlow exists
 * - CashFlow is not in SETUP mode
 * - Category exists and is active (if categoryName is provided)
 * <p>
 * Returns a response with:
 * - updatedCount: number of PENDING cash changes updated
 * - skippedCount: number of cash changes skipped (CONFIRMED or not found)
 */
@Slf4j
@Component
@AllArgsConstructor
public class BatchUpdateCashChangesCommandHandler implements CommandHandler<BatchUpdateCashChangesCommand, BatchUpdateResult> {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CashFlowEventEmitter cashFlowEventEmitter;
    private final Clock clock;

    @Override
    public BatchUpdateResult handle(BatchUpdateCashChangesCommand command) {
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();

        // Validate: operation not allowed in SETUP mode
        if (CashFlow.CashFlowStatus.SETUP.equals(snapshot.status())) {
            throw new OperationNotAllowedInSetupModeException("batchUpdateCashChanges", command.cashFlowId());
        }

        if (command.cashChangeIds() == null || command.cashChangeIds().isEmpty()) {
            log.info("No cash change IDs provided for batch update in CashFlow [{}]", command.cashFlowId());
            return new BatchUpdateResult(0, 0);
        }

        // Filter to only PENDING cash changes that exist
        List<CashChangeId> updatedIds = new ArrayList<>();
        List<CashChangeSnapshot> pendingCashChanges = new ArrayList<>();
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
                updatedIds.add(cashChangeId);
                pendingCashChanges.add(cashChange);
            }
        }

        if (updatedIds.isEmpty()) {
            log.info("No PENDING cash changes to update for sourceRuleId [{}] in CashFlow [{}]",
                    command.sourceRuleId(), command.cashFlowId());
            return new BatchUpdateResult(0, skippedCount);
        }

        // Validate category if provided
        if (command.updates().categoryName() != null) {
            // Determine the type from the first cash change (all should be same type for a rule)
            Type type = pendingCashChanges.get(0).type();
            List<Category> categories = type == Type.INFLOW
                    ? snapshot.inflowCategories()
                    : snapshot.outflowCategories();

            Category activeCategory = findActiveCategory(categories, command.updates().categoryName());
            if (activeCategory == null) {
                Category archivedCategory = findArchivedCategory(categories, command.updates().categoryName());
                if (archivedCategory != null) {
                    throw new CategoryIsArchivedException(command.updates().categoryName());
                }
                throw new CategoryDoesNotExistsException(command.updates().categoryName());
            }
        }

        // Build the changes map
        Map<String, Object> changes = new HashMap<>();
        if (command.updates().amount() != null) {
            changes.put("amount", command.updates().amount());
        }
        if (command.updates().name() != null) {
            changes.put("name", command.updates().name());
        }
        if (command.updates().categoryName() != null) {
            changes.put("categoryName", command.updates().categoryName());
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        CashFlowEvent.CashChangesBatchUpdatedEvent event = new CashFlowEvent.CashChangesBatchUpdatedEvent(
                command.cashFlowId(),
                command.sourceRuleId(),
                updatedIds,
                changes,
                now
        );

        cashFlow.apply(event);

        domainCashFlowRepository.save(cashFlow);

        cashFlowEventEmitter.emit(
                CashFlowUnifiedEvent.builder()
                        .metadata(Map.of("event", CashFlowEvent.CashChangesBatchUpdatedEvent.class.getSimpleName()))
                        .content(JsonContent.asPrettyJson(event))
                        .build()
        );

        log.info("Batch updated [{}] cash changes for sourceRuleId [{}] in CashFlow [{}], skipped [{}]. Changes: {}",
                updatedIds.size(), command.sourceRuleId(), command.cashFlowId(), skippedCount, changes.keySet());

        return new BatchUpdateResult(updatedIds.size(), skippedCount);
    }

    private Category findActiveCategory(List<Category> categories, CategoryName categoryName) {
        for (Category category : categories) {
            if (category.getCategoryName().equals(categoryName) && category.isActive()) {
                return category;
            }
            Category found = findActiveCategory(category.getSubCategories(), categoryName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Category findArchivedCategory(List<Category> categories, CategoryName categoryName) {
        for (Category category : categories) {
            if (category.getCategoryName().equals(categoryName) && category.isArchived()) {
                return category;
            }
            Category found = findArchivedCategory(category.getSubCategories(), categoryName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
