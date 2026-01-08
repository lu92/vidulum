package com.multi.vidulum.bank_data_ingestion.app.commands.start_import;

import com.multi.vidulum.bank_data_ingestion.domain.ImportJob;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobId;
import com.multi.vidulum.bank_data_ingestion.domain.ImportJobStatus;
import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.util.List;

/**
 * Result of starting an import job.
 */
public record StartImportJobResult(
        ImportJobId jobId,
        CashFlowId cashFlowId,
        StagingSessionId stagingSessionId,
        ImportJobStatus status,
        InputSummary input,
        ImportJob.ImportProgress progress,
        ImportJob.ImportResult result,
        ImportJob.ImportSummary summary,
        boolean canRollback,
        String pollUrl
) {

    /**
     * Summary of input data.
     */
    public record InputSummary(
            int totalTransactions,
            int validTransactions,
            int duplicateTransactions,
            int categoriesToCreate
    ) {
        public static InputSummary from(ImportJob.ImportInput input) {
            return new InputSummary(
                    input.totalTransactions(),
                    input.validTransactions(),
                    input.duplicateTransactions(),
                    input.categoriesToCreate()
            );
        }
    }

    /**
     * Create result from an ImportJob.
     */
    public static StartImportJobResult from(ImportJob job, String baseUrl) {
        String pollUrl = baseUrl + "/import/" + job.jobId().id();

        return new StartImportJobResult(
                job.jobId(),
                job.cashFlowId(),
                job.stagingSessionId(),
                job.status(),
                InputSummary.from(job.input()),
                job.progress(),
                job.result(),
                job.summary(),
                job.canRollback(),
                pollUrl
        );
    }
}
