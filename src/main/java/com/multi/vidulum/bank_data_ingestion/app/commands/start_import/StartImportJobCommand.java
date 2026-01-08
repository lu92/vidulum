package com.multi.vidulum.bank_data_ingestion.app.commands.start_import;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to start an import job from staged transactions.
 * This will create categories and import transactions into the CashFlow.
 *
 * @param cashFlowId       the CashFlow to import into
 * @param stagingSessionId the staging session containing transactions to import
 */
public record StartImportJobCommand(
        CashFlowId cashFlowId,
        StagingSessionId stagingSessionId
) implements Command {
}
