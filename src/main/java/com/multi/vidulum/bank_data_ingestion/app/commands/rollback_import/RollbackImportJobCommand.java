package com.multi.vidulum.bank_data_ingestion.app.commands.rollback_import;

import com.multi.vidulum.bank_data_ingestion.domain.ImportJobId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to rollback an import job.
 * This will delete all imported transactions and optionally the created categories.
 *
 * @param cashFlowId the CashFlow ID
 * @param jobId      the import job ID
 */
public record RollbackImportJobCommand(
        CashFlowId cashFlowId,
        ImportJobId jobId
) implements Command {
}
