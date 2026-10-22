package com.multi.vidulum.bank_data_ingestion.mappings.commands.delete_mapping;

import com.multi.vidulum.bank_data_ingestion.mappings.domain.MappingId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import com.multi.vidulum.bank_data_ingestion.mappings.domain.*;

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
