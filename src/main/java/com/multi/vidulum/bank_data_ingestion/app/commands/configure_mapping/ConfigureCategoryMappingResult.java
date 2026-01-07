package com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping;

import com.multi.vidulum.bank_data_ingestion.domain.CategoryMapping;
import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.util.List;

/**
 * Result of configuring category mappings.
 *
 * @param cashFlowId          the CashFlow mappings were configured for
 * @param mappingsConfigured  total number of mappings configured
 * @param configuredMappings  list of all configured mappings with their status
 */
public record ConfigureCategoryMappingResult(
        CashFlowId cashFlowId,
        int mappingsConfigured,
        List<MappingResult> configuredMappings
) {

    /**
     * Result for a single mapping configuration.
     *
     * @param mapping the configured mapping
     * @param status  whether the mapping was CREATED or UPDATED
     */
    public record MappingResult(
            CategoryMapping mapping,
            MappingStatus status
    ) {
    }

    public enum MappingStatus {
        CREATED,
        UPDATED
    }
}
