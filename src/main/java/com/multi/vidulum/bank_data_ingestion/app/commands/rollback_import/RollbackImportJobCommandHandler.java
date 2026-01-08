package com.multi.vidulum.bank_data_ingestion.app.commands.rollback_import;

import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.app.commands.rollbackimport.RollbackImportCommand;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * Handler for rolling back an import job.
 * This uses the existing RollbackImportCommand to delete transactions and categories,
 * and updates the ImportJob status.
 */
@Slf4j
@Component
public class RollbackImportJobCommandHandler implements CommandHandler<RollbackImportJobCommand, RollbackImportJobResult> {

    private final ImportJobRepository importJobRepository;
    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CommandGateway commandGateway;
    private final Clock clock;

    public RollbackImportJobCommandHandler(
            ImportJobRepository importJobRepository,
            DomainCashFlowRepository domainCashFlowRepository,
            @Lazy CommandGateway commandGateway,
            Clock clock) {
        this.importJobRepository = importJobRepository;
        this.domainCashFlowRepository = domainCashFlowRepository;
        this.commandGateway = commandGateway;
        this.clock = clock;
    }

    @Override
    public RollbackImportJobResult handle(RollbackImportJobCommand command) {
        ZonedDateTime startTime = ZonedDateTime.now(clock);

        // Load the import job
        ImportJob job = importJobRepository.findById(command.jobId())
                .orElseThrow(() -> new ImportJobNotFoundException(command.jobId()));

        // Verify the job belongs to the requested CashFlow
        if (!job.cashFlowId().equals(command.cashFlowId())) {
            throw new ImportJobNotFoundException(command.jobId());
        }

        // Check if rollback is allowed
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (!job.canRollback()) {
            throw new RollbackNotAllowedException(command.jobId(), "Rollback is not allowed for this job");
        }

        if (job.isRollbackDeadlinePassed(now)) {
            throw new RollbackNotAllowedException(command.jobId(), "Rollback deadline has passed");
        }

        // Check CashFlow status
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();
        if (snapshot.status() != CashFlow.CashFlowStatus.SETUP) {
            throw new RollbackNotAllowedException(command.jobId(),
                    "CashFlow is not in SETUP mode. Rollback is not possible after attestation.");
        }

        // Count transactions and categories before rollback
        int transactionsBefore = snapshot.cashChanges().size();
        int categoriesBefore = countCategories(snapshot);

        // Execute rollback - delete all transactions and optionally categories
        boolean deleteCategories = !job.result().categoriesCreated().isEmpty();
        RollbackImportCommand rollbackCmd = new RollbackImportCommand(command.cashFlowId(), deleteCategories);
        CashFlowSnapshot snapshotAfter = commandGateway.send(rollbackCmd);

        // Calculate what was deleted
        int transactionsDeleted = transactionsBefore - snapshotAfter.cashChanges().size();
        int categoriesDeleted = deleteCategories ? (categoriesBefore - countCategories(snapshotAfter)) : 0;

        ZonedDateTime endTime = ZonedDateTime.now(clock);
        long durationMs = java.time.Duration.between(startTime, endTime).toMillis();

        // Update the import job
        ImportJob updatedJob = job.rollback(transactionsDeleted, categoriesDeleted, durationMs, endTime);
        updatedJob = importJobRepository.save(updatedJob);

        log.info("Import job [{}] rolled back. Deleted {} transactions, {} categories in {}ms",
                command.jobId().id(), transactionsDeleted, categoriesDeleted, durationMs);

        return RollbackImportJobResult.from(updatedJob);
    }

    private int countCategories(CashFlowSnapshot snapshot) {
        return countCategoriesRecursive(snapshot.inflowCategories())
                + countCategoriesRecursive(snapshot.outflowCategories());
    }

    private int countCategoriesRecursive(java.util.List<com.multi.vidulum.cashflow.domain.Category> categories) {
        int count = 0;
        for (var cat : categories) {
            if (!cat.getCategoryName().name().equals("Uncategorized")) {
                count++;
            }
            count += countCategoriesRecursive(cat.getSubCategories());
        }
        return count;
    }
}
