package com.multi.vidulum.bank_data_ingestion.app.commands.delete_all_mappings;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to delete all category mappings for a CashFlow.
 *
 * @param cashFlowId the CashFlow to delete all mappings for
 */
public record DeleteAllCategoryMappingsCommand(
        CashFlowId cashFlowId
) implements Command {
}
