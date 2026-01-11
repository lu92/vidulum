package com.multi.vidulum.bank_data_ingestion.app.queries.list_staging_sessions;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.queries.Query;

/**
 * Query to list all active (non-expired) staging sessions for a CashFlow.
 * Used to allow users to return to unfinished imports.
 */
public record ListStagingSessionsQuery(
        CashFlowId cashFlowId
) implements Query {
}
