package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

import java.util.List;

/**
 * Exception thrown when staging transactions contain unmapped bank categories.
 */
public class UnmappedCategoriesException extends BusinessException {

    private final List<UnmappedCategory> unmappedCategories;

    public UnmappedCategoriesException(List<UnmappedCategory> unmappedCategories) {
        super("Some bank categories are not mapped: " + unmappedCategories);
        this.unmappedCategories = unmappedCategories;
    }

    public List<UnmappedCategory> getUnmappedCategories() {
        return unmappedCategories;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INGESTION_UNMAPPED_CATEGORIES;
    }

    public record UnmappedCategory(String bankCategory, int count, Type type) {
    }
}
