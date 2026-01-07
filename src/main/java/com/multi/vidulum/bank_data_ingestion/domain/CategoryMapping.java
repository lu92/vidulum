package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;

import java.time.ZonedDateTime;

/**
 * Domain object representing a mapping from a bank category to a system category.
 * Used during bank data ingestion to map bank transaction categories to CashFlow categories.
 *
 * @param mappingId          unique identifier for this mapping
 * @param cashFlowId         the CashFlow this mapping belongs to
 * @param bankCategoryName   the original category name from bank data (e.g., "Zakupy kartą")
 * @param targetCategoryName the target category name in the system (e.g., "Groceries")
 * @param parentCategoryName the parent category name for CREATE_SUBCATEGORY action (nullable)
 * @param categoryType       INFLOW or OUTFLOW
 * @param action             what action to take when this mapping is applied
 * @param createdAt          when this mapping was created
 * @param updatedAt          when this mapping was last updated
 */
public record CategoryMapping(
        MappingId mappingId,
        CashFlowId cashFlowId,
        String bankCategoryName,
        CategoryName targetCategoryName,
        CategoryName parentCategoryName,
        Type categoryType,
        MappingAction action,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt
) {

    public static CategoryMapping create(
            CashFlowId cashFlowId,
            String bankCategoryName,
            CategoryName targetCategoryName,
            CategoryName parentCategoryName,
            Type categoryType,
            MappingAction action,
            ZonedDateTime now
    ) {
        return new CategoryMapping(
                MappingId.generate(),
                cashFlowId,
                bankCategoryName,
                targetCategoryName,
                parentCategoryName,
                categoryType,
                action,
                now,
                now
        );
    }

    public CategoryMapping withUpdatedAt(ZonedDateTime updatedAt) {
        return new CategoryMapping(
                this.mappingId,
                this.cashFlowId,
                this.bankCategoryName,
                this.targetCategoryName,
                this.parentCategoryName,
                this.categoryType,
                this.action,
                this.createdAt,
                updatedAt
        );
    }

    public CategoryMapping update(
            CategoryName targetCategoryName,
            CategoryName parentCategoryName,
            MappingAction action,
            ZonedDateTime now
    ) {
        return new CategoryMapping(
                this.mappingId,
                this.cashFlowId,
                this.bankCategoryName,
                targetCategoryName,
                parentCategoryName,
                this.categoryType,
                action,
                this.createdAt,
                now
        );
    }
}
