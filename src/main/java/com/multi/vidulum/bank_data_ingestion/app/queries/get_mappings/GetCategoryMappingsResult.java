package com.multi.vidulum.bank_data_ingestion.app.queries.get_mappings;

import com.multi.vidulum.bank_data_ingestion.domain.CategoryMapping;
import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.util.List;

/**
 * Result of the GetCategoryMappings query.
 *
 * @param cashFlowId    the CashFlow the mappings belong to
 * @param mappingsCount total number of mappings
 * @param mappings      list of all category mappings
 */
public record GetCategoryMappingsResult(
        CashFlowId cashFlowId,
        int mappingsCount,
        List<CategoryMapping> mappings
) {
}
