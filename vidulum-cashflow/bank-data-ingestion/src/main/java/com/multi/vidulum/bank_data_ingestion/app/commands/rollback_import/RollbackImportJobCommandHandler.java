package com.multi.vidulum.bank_data_ingestion.app.commands.rollback_import;

import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.RollbackResult;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.bank_data_ingestion.domain.BankDataIngestionEvent.ImportJobRolledBackEvent;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * Handler for rolling back an import job.
 * This uses CashFlowModuleApi to delete transactions and categories,
 * and updates the ImportJob status.
 */
@Slf4j
@Component
@AllArgsConstructor
public class RollbackImportJobCommandHandler implements CommandHandler<RollbackImportJobCommand, RollbackImportJobResult> {

    private final ImportJobRepository importJobRepository;
    private final CashFlowModuleApi cashFlowApi;
    private final BankDataIngestionEventEmitter eventEmitter;
    private final Clock clock;

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

        // The skeleton, not getCashFlowInfo: this handler wanted one boolean and was paying two
        // HTTP requests and a payload carrying every transaction for it. No try/catch either - the
        // contract throws CashFlowNotFoundException, which carries an ErrorCode and answers 404.
        if (!cashFlowApi.skeletonOf(command.cashFlowId()).isInSetup()) {
            throw new RollbackNotAllowedException(command.jobId(),
                    "CashFlow is not in SETUP mode. Rollback is not possible after attestation.");
        }

        // Execute rollback - delete all transactions and optionally categories
        boolean deleteCategories = !job.result().categoriesCreated().isEmpty();
        // One request, and the counts are the server's own. The port this replaces read the state,
        // deleted, read it again and subtracted - five requests, and wrong if anything changed in
        // between.
        RollbackResult rollbackResult = cashFlowApi.rollbackImport(command.cashFlowId(), deleteCategories);

        int transactionsDeleted = rollbackResult.transactionsDeleted();
        int categoriesDeleted = rollbackResult.categoriesDeleted();

        ZonedDateTime endTime = ZonedDateTime.now(clock);
        long durationMs = java.time.Duration.between(startTime, endTime).toMillis();

        // Update the import job
        ImportJob updatedJob = job.rollback(transactionsDeleted, categoriesDeleted, durationMs, endTime);
        updatedJob = importJobRepository.save(updatedJob);

        log.info("Import job [{}] rolled back. Deleted {} transactions, {} categories in {}ms",
                command.jobId().id(), transactionsDeleted, categoriesDeleted, durationMs);

        // Emit rollback event
        eventEmitter.emit(new ImportJobRolledBackEvent(
                command.jobId().id(),
                command.cashFlowId().id(),
                transactionsDeleted,
                categoriesDeleted,
                durationMs,
                endTime
        ));

        return RollbackImportJobResult.from(updatedJob);
    }
}
