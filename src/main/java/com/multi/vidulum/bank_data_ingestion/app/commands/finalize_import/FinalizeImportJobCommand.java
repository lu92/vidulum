package com.multi.vidulum.bank_data_ingestion.app.commands.finalize_import;

import com.multi.vidulum.bank_data_ingestion.domain.ImportJobId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to finalize an import job.
 * This will delete staging data and optionally the category mappings.
 * The imported data remains in the CashFlow.
 *
 * @param cashFlowId     the CashFlow ID
 * @param jobId          the import job ID
 * @param deleteMappings whether to delete category mappings (default: false)
 */
public record FinalizeImportJobCommand(
        CashFlowId cashFlowId,
        ImportJobId jobId,
        boolean deleteMappings
) implements Command {

    /**
     * Create command without deleting mappings.
     */
    public static FinalizeImportJobCommand keepMappings(CashFlowId cashFlowId, ImportJobId jobId) {
        return new FinalizeImportJobCommand(cashFlowId, jobId, false);
    }

    /**
     * Create command that deletes mappings.
     */
    public static FinalizeImportJobCommand withDeleteMappings(CashFlowId cashFlowId, ImportJobId jobId) {
        return new FinalizeImportJobCommand(cashFlowId, jobId, true);
    }
}
