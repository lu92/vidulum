package com.multi.vidulum.bank_data_ingestion.app.commands.delete_staging_session;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to delete a staging session and all its staged transactions.
 *
 * @param cashFlowId       the CashFlow ID
 * @param stagingSessionId the staging session to delete
 */
public record DeleteStagingSessionCommand(
        CashFlowId cashFlowId,
        StagingSessionId stagingSessionId
) implements Command {
}
