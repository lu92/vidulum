package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for CategoryMapping persistence operations.
 */
public interface CategoryMappingRepository {

    /**
     * Save a category mapping.
     */
    CategoryMapping save(CategoryMapping mapping);

    /**
     * Find a mapping by its ID.
     */
    Optional<CategoryMapping> findById(MappingId mappingId);

    /**
     * Find a mapping by cash flow ID, bank category name, and category type.
     * This combination forms a unique key.
     */
    Optional<CategoryMapping> findByCashFlowIdAndBankCategoryNameAndCategoryType(
            CashFlowId cashFlowId,
            String bankCategoryName,
            Type categoryType
    );

    /**
     * Find all mappings for a given CashFlow.
     */
    List<CategoryMapping> findByCashFlowId(CashFlowId cashFlowId);

    /**
     * Delete a mapping by its ID.
     */
    void deleteById(MappingId mappingId);

    /**
     * Delete all mappings for a given CashFlow.
     */
    long deleteByCashFlowId(CashFlowId cashFlowId);
}
