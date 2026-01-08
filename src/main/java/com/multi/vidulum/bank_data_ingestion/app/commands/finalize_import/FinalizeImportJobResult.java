package com.multi.vidulum.bank_data_ingestion.app.commands.finalize_import;

import com.multi.vidulum.bank_data_ingestion.domain.ImportJob;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobId;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobStatus;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Result of finalizing an import job.
 */
public record FinalizeImportJobResult(
        ImportJobId jobId,
        ImportJobStatus status,
        CleanupSummary cleanup,
        FinalSummary finalSummary
) {

    /**
     * Summary of cleanup operations.
     */
    public record CleanupSummary(
            long stagedTransactionsDeleted,
            long mappingsDeleted
    ) {
    }

    /**
     * Final summary of the import.
     */
    public record FinalSummary(
            ZonedDateTime importedAt,
            long totalDurationMs,
            int categoriesCreated,
            int transactionsImported,
            List<ImportJob.CategoryBreakdown> categoryBreakdown
    ) {
        public static FinalSummary from(ImportJob job) {
            return new FinalSummary(
                    job.timestamps().completedAt(),
                    job.summary() != null ? job.summary().totalDurationMs() : 0,
                    job.result().categoriesCreated().size(),
                    job.result().transactionsImported(),
                    job.summary() != null ? job.summary().categoryBreakdown() : List.of()
            );
        }
    }

    /**
     * Create result from ImportJob after finalization.
     */
    public static FinalizeImportJobResult from(ImportJob job, long stagedDeleted, long mappingsDeleted) {
        return new FinalizeImportJobResult(
                job.jobId(),
                job.status(),
                new CleanupSummary(stagedDeleted, mappingsDeleted),
                FinalSummary.from(job)
        );
    }
}
