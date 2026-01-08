package com.multi.vidulum.bank_data_ingestion.app.commands.delete_staging_session;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;

/**
 * Result of deleting a staging session.
 *
 * @param cashFlowId       the CashFlow ID
 * @param stagingSessionId the deleted staging session ID
 * @param deleted          whether the deletion was successful
 * @param deletedCount     number of staged transactions deleted
 */
public record DeleteStagingSessionResult(
        CashFlowId cashFlowId,
        StagingSessionId stagingSessionId,
        boolean deleted,
        long deletedCount
) {
}
