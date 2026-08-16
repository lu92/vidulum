package com.multi.vidulum.bank_data_ingestion.app.commands.rollback_import;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.bank_data_ingestion.domain.BankDataIngestionEvent.ImportJobRolledBackEvent;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * Handler for rolling back an import job.
 * This uses the CashFlowServiceClient to delete transactions and categories,
 * and updates the ImportJob status.
 */
@Slf4j
@Component
@AllArgsConstructor
public class RollbackImportJobCommandHandler implements CommandHandler<RollbackImportJobCommand, RollbackImportJobResult> {

    private final ImportJobRepository importJobRepository;
    private final CashFlowServiceClient cashFlowServiceClient;
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

        // Check CashFlow status
        CashFlowInfo cashFlowInfo;
        try {
            cashFlowInfo = cashFlowServiceClient.getCashFlowInfo(command.cashFlowId().id());
        } catch (CashFlowServiceClient.CashFlowNotFoundException e) {
            throw new CashFlowDoesNotExistsException(command.cashFlowId());
        }

        if (!cashFlowInfo.isInSetupMode()) {
            throw new RollbackNotAllowedException(command.jobId(),
                    "CashFlow is not in SETUP mode. Rollback is not possible after attestation.");
        }

        // Execute rollback - delete all transactions and optionally categories
        boolean deleteCategories = !job.result().categoriesCreated().isEmpty();
        CashFlowServiceClient.RollbackResult rollbackResult =
                cashFlowServiceClient.rollbackImport(command.cashFlowId().id(), deleteCategories);

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
