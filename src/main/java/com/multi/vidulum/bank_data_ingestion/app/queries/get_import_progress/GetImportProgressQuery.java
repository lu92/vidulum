package com.multi.vidulum.bank_data_ingestion.app.queries.get_import_progress;

import com.multi.vidulum.bank_data_ingestion.domain.ImportJobId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.queries.Query;

/**
 * Query to get the current progress of an import job.
 *
 * @param cashFlowId the CashFlow ID
 * @param jobId      the import job ID
 */
public record GetImportProgressQuery(
        CashFlowId cashFlowId,
        ImportJobId jobId
) implements Query {
}
