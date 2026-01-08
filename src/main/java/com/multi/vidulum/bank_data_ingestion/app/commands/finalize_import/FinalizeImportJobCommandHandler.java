package com.multi.vidulum.bank_data_ingestion.app.commands.finalize_import;

import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * Handler for finalizing an import job.
 * This deletes staging data and optionally the category mappings,
 * and marks the job as finalized.
 */
@Slf4j
@Component
@AllArgsConstructor
public class FinalizeImportJobCommandHandler implements CommandHandler<FinalizeImportJobCommand, FinalizeImportJobResult> {

    private final ImportJobRepository importJobRepository;
    private final StagedTransactionRepository stagedTransactionRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final Clock clock;

    @Override
    public FinalizeImportJobResult handle(FinalizeImportJobCommand command) {
        ZonedDateTime now = ZonedDateTime.now(clock);

        // Load the import job
        ImportJob job = importJobRepository.findById(command.jobId())
                .orElseThrow(() -> new ImportJobNotFoundException(command.jobId()));

        // Verify the job belongs to the requested CashFlow
        if (!job.cashFlowId().equals(command.cashFlowId())) {
            throw new ImportJobNotFoundException(command.jobId());
        }

        // Check if job is in COMPLETED status
        if (job.status() != ImportJobStatus.COMPLETED) {
            throw new ImportJobNotCompletedException(command.jobId(), job.status());
        }

        // Delete staged transactions for this session
        long stagedDeleted = stagedTransactionRepository.deleteByStagingSessionId(job.stagingSessionId());
        log.debug("Deleted {} staged transactions for session [{}]",
                stagedDeleted, job.stagingSessionId().id());

        // Optionally delete mappings
        long mappingsDeleted = 0;
        if (command.deleteMappings()) {
            mappingsDeleted = categoryMappingRepository.deleteByCashFlowId(command.cashFlowId());
            log.debug("Deleted {} category mappings for CashFlow [{}]",
                    mappingsDeleted, command.cashFlowId().id());
        }

        // Finalize the job
        ImportJob finalizedJob = job.finalize(now);
        finalizedJob = importJobRepository.save(finalizedJob);

        log.info("Import job [{}] finalized. Deleted {} staged transactions, {} mappings",
                command.jobId().id(), stagedDeleted, mappingsDeleted);

        return FinalizeImportJobResult.from(finalizedJob, stagedDeleted, mappingsDeleted);
    }
}
