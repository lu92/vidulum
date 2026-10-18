package com.multi.vidulum.bank_data_ingestion.app.commands.delete_all_mappings;

/**
 * Result of deleting all category mappings.
 *
 * @param deleted      true if mappings were deleted
 * @param deletedCount number of mappings deleted
 */
public record DeleteAllCategoryMappingsResult(
        boolean deleted,
        long deletedCount
) {
}
