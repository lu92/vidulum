package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Exception thrown when a category mapping is not found.
 */
public class CategoryMappingNotFoundException extends RuntimeException {

    public CategoryMappingNotFoundException(MappingId mappingId) {
        super("Category mapping not found: " + mappingId.id());
    }
}
