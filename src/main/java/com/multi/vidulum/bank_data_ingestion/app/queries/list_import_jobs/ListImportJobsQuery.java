package com.multi.vidulum.bank_data_ingestion.app.queries.list_import_jobs;

import com.multi.vidulum.bank_data_ingestion.domain.ImportJobStatus;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.queries.Query;

import java.util.List;

/**
 * Query to list import jobs for a CashFlow.
 *
 * @param cashFlowId the CashFlow ID
 * @param statuses   optional filter by statuses (null or empty means all)
 */
public record ListImportJobsQuery(
        CashFlowId cashFlowId,
        List<ImportJobStatus> statuses
) implements Query {

    /**
     * Create a query for all jobs.
     */
    public static ListImportJobsQuery all(CashFlowId cashFlowId) {
        return new ListImportJobsQuery(cashFlowId, null);
    }

    /**
     * Create a query with status filter.
     */
    public static ListImportJobsQuery withStatuses(CashFlowId cashFlowId, List<ImportJobStatus> statuses) {
        return new ListImportJobsQuery(cashFlowId, statuses);
    }
}
