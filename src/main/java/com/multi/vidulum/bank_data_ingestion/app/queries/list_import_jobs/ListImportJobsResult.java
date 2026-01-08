package com.multi.vidulum.bank_data_ingestion.app.queries.list_import_jobs;

import com.multi.vidulum.bank_data_ingestion.domain.ImportJob;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobId;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobStatus;
import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Result of listing import jobs.
 */
public record ListImportJobsResult(
        CashFlowId cashFlowId,
        List<ImportJobSummary> jobs
) {

    /**
     * Summary of an import job for listing.
     */
    public record ImportJobSummary(
            ImportJobId jobId,
            ImportJobStatus status,
            ZonedDateTime createdAt,
            ZonedDateTime completedAt,
            int transactionsImported,
            int categoriesCreated,
            boolean canRollback
    ) {
        public static ImportJobSummary from(ImportJob job, ZonedDateTime now) {
            return new ImportJobSummary(
                    job.jobId(),
                    job.status(),
                    job.timestamps().createdAt(),
                    job.timestamps().completedAt(),
                    job.result().transactionsImported(),
                    job.result().categoriesCreated().size(),
                    job.canRollback() && !job.isRollbackDeadlinePassed(now)
            );
        }
    }

    /**
     * Create result from list of ImportJobs.
     */
    public static ListImportJobsResult from(CashFlowId cashFlowId, List<ImportJob> jobs, ZonedDateTime now) {
        List<ImportJobSummary> summaries = jobs.stream()
                .map(job -> ImportJobSummary.from(job, now))
                .toList();

        return new ListImportJobsResult(cashFlowId, summaries);
    }
}
