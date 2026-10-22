package com.multi.vidulum.bank_data_ingestion.mappings.commands.delete_mapping;

import com.multi.vidulum.bank_data_ingestion.mappings.domain.MappingId;
import com.multi.vidulum.bank_data_ingestion.mappings.domain.*;

/**
 * Result of deleting a single category mapping.
 *
 * @param deleted          true if the mapping was deleted
 * @param mappingId        the ID of the deleted mapping
 * @param bankCategoryName the bank category name of the deleted mapping
 */
public record DeleteCategoryMappingResult(
        boolean deleted,
        MappingId mappingId,
        String bankCategoryName
) {
}
