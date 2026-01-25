package com.multi.vidulum.bank_data_ingestion.app.commands.revalidate_staging;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to revalidate a staging session after category mappings have been configured.
 * This updates transactions that were PENDING_MAPPING to have proper mapped data.
 */
public record RevalidateStagingCommand(
        CashFlowId cashFlowId,
        StagingSessionId stagingSessionId
) implements Command {
}
