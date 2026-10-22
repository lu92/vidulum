package com.multi.vidulum.bank_data_ingestion.staging.commands.force_uncategorized;

import com.multi.vidulum.bank_data_ingestion.staging.domain.StagingSessionId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to force all PENDING_MAPPING transactions to use "Uncategorized" category.
 * This is an explicit user decision to proceed without categorizing transactions.
 *
 * The "Uncategorized" category will be created automatically if it doesn't exist.
 */
public record ForceUncategorizedCommand(
        CashFlowId cashFlowId,
        StagingSessionId stagingSessionId
) implements Command {
}
