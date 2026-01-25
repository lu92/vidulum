package com.multi.vidulum.bank_data_ingestion.app.commands.revalidate_staging;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.util.List;

/**
 * Result of revalidating a staging session.
 */
public record RevalidateStagingResult(
        StagingSessionId stagingSessionId,
        CashFlowId cashFlowId,
        Status status,
        RevalidationSummary summary,
        List<String> stillUnmappedCategories
) {

    public enum Status {
        SUCCESS,           // All transactions now have mappings
        STILL_UNMAPPED,    // Some categories still don't have mappings
        SESSION_NOT_FOUND, // Staging session doesn't exist
        SESSION_EXPIRED    // Staging session has expired
    }

    public record RevalidationSummary(
            int totalTransactions,
            int revalidatedCount,
            int stillPendingCount,
            int validCount,
            int invalidCount,
            int duplicateCount
    ) {
    }
}
