package com.multi.vidulum.bank_data_ingestion.app.queries.get_import_progress;

import com.multi.vidulum.bank_data_ingestion.domain.ImportJob;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobId;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobStatus;
import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.time.ZonedDateTime;

/**
 * Result of getting import progress.
 */
public record GetImportProgressResult(
        ImportJobId jobId,
        CashFlowId cashFlowId,
        ImportJobStatus status,
        ImportJob.ImportProgress progress,
        ImportJob.ImportResult result,
        ImportJob.ImportSummary summary,
        boolean canRollback,
        ZonedDateTime rollbackDeadline,
        long elapsedTimeMs
) {

    /**
     * Create result from an ImportJob.
     */
    public static GetImportProgressResult from(ImportJob job, ZonedDateTime now) {
        return new GetImportProgressResult(
                job.jobId(),
                job.cashFlowId(),
                job.status(),
                job.progress(),
                job.result(),
                job.summary(),
                job.canRollback() && !job.isRollbackDeadlinePassed(now),
                job.rollbackData().rollbackDeadline(),
                job.getElapsedTimeMs(now)
        );
    }
}
