package com.multi.vidulum.bank_data_ingestion.app.commands.delete_mapping;

import com.multi.vidulum.bank_data_ingestion.domain.MappingId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to delete a single category mapping.
 *
 * @param cashFlowId the CashFlow the mapping belongs to
 * @param mappingId  the ID of the mapping to delete
 */
public record DeleteCategoryMappingCommand(
        CashFlowId cashFlowId,
        MappingId mappingId
) implements Command {
}
