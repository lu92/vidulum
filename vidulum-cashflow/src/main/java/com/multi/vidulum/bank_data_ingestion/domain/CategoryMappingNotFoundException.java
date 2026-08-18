package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Exception thrown when a category mapping is not found.
 */
public class CategoryMappingNotFoundException extends BusinessException {

    public CategoryMappingNotFoundException(MappingId mappingId) {
        super("Category mapping not found: " + mappingId.id());
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INGESTION_MAPPING_NOT_FOUND;
    }
}
