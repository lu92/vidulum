package com.multi.vidulum.bank_data_ingestion.importing.commands.rollback_import;

import com.multi.vidulum.bank_data_ingestion.importing.domain.ImportJob;
import com.multi.vidulum.bank_data_ingestion.importing.domain.ImportJobId;
import com.multi.vidulum.bank_data_ingestion.importing.domain.ImportJobStatus;
import com.multi.vidulum.bank_data_ingestion.importing.domain.*;

/**
 * Result of rolling back an import job.
 */
public record RollbackImportJobResult(
        ImportJobId jobId,
        ImportJobStatus status,
        RollbackSummary rollbackSummary
) {

    /**
     * Summary of the rollback operation.
     */
    public record RollbackSummary(
            int transactionsDeleted,
            int categoriesDeleted,
            long rollbackDurationMs
    ) {
        public static RollbackSummary from(ImportJob.RollbackSummary summary) {
            return new RollbackSummary(
                    summary.transactionsDeleted(),
                    summary.categoriesDeleted(),
                    summary.rollbackDurationMs()
            );
        }
    }

    /**
     * Create result from an ImportJob after rollback.
     */
    public static RollbackImportJobResult from(ImportJob job) {
        RollbackSummary summary = job.summary() != null && job.summary().rollbackSummary() != null
                ? RollbackSummary.from(job.summary().rollbackSummary())
                : new RollbackSummary(0, 0, 0);

        return new RollbackImportJobResult(
                job.jobId(),
                job.status(),
                summary
        );
    }
}
