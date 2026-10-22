package com.multi.vidulum.bank_data_ingestion.importing.queries.get_import_progress;

import com.multi.vidulum.bank_data_ingestion.importing.domain.ImportJobId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.shared.cqrs.queries.Query;
import com.multi.vidulum.bank_data_ingestion.importing.domain.*;

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
