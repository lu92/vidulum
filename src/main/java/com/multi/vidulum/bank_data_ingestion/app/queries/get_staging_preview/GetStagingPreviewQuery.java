package com.multi.vidulum.bank_data_ingestion.app.queries.get_staging_preview;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.queries.Query;

/**
 * Query to get a preview of staged transactions for a given staging session.
 *
 * @param cashFlowId       the CashFlow ID
 * @param stagingSessionId the staging session to get preview for
 */
public record GetStagingPreviewQuery(
        CashFlowId cashFlowId,
        StagingSessionId stagingSessionId
) implements Query {
}
