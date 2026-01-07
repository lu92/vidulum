package com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping;

import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.util.List;

/**
 * Command to configure category mappings for bank data ingestion.
 * This allows users to define how bank transaction categories should be mapped
 * to system categories during import.
 *
 * @param cashFlowId the CashFlow to configure mappings for
 * @param mappings   list of mapping configurations
 */
public record ConfigureCategoryMappingCommand(
        CashFlowId cashFlowId,
        List<MappingConfig> mappings
) implements Command {

    /**
     * Configuration for a single category mapping.
     *
     * @param bankCategoryName   the category name from bank data
     * @param targetCategoryName the target category name in the system
     * @param parentCategoryName parent category for CREATE_SUBCATEGORY action (nullable)
     * @param categoryType       INFLOW or OUTFLOW
     * @param action             what action to take for this mapping
     */
    public record MappingConfig(
            String bankCategoryName,
            CategoryName targetCategoryName,
            CategoryName parentCategoryName,
            Type categoryType,
            MappingAction action
    ) {
    }
}
