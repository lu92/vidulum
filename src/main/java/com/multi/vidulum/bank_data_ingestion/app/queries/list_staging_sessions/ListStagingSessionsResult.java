package com.multi.vidulum.bank_data_ingestion.app.queries.list_staging_sessions;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Result containing all active staging sessions for a CashFlow.
 */
public record ListStagingSessionsResult(
        CashFlowId cashFlowId,
        List<StagingSessionSummary> stagingSessions,
        boolean hasPendingImport
) {

    /**
     * Summary of a single staging session.
     */
    public record StagingSessionSummary(
            StagingSessionId stagingSessionId,
            String status,
            ZonedDateTime createdAt,
            ZonedDateTime expiresAt,
            TransactionCounts counts
    ) {}

    /**
     * Transaction counts for a staging session.
     */
    public record TransactionCounts(
            int totalTransactions,
            int validTransactions,
            int invalidTransactions,
            int duplicateTransactions
    ) {}
}
