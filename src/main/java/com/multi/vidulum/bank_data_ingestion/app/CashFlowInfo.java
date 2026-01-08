package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.cashflow.domain.Type;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/**
 * DTO representing CashFlow information needed by bank-data-ingestion module.
 * This abstraction allows the module to be decoupled from the CashFlow domain,
 * enabling future migration to a microservice architecture.
 */
public record CashFlowInfo(
        String cashFlowId,
        CashFlowStatus status,
        YearMonth activePeriod,
        YearMonth startPeriod,
        List<CategoryInfo> inflowCategories,
        List<CategoryInfo> outflowCategories,
        Set<String> existingTransactionIds,
        int cashChangesCount
) {

    /**
     * CashFlow status enum - mirrors the domain status.
     */
    public enum CashFlowStatus {
        SETUP,
        OPEN,
        CLOSED
    }

    /**
     * Category information needed for staging and import operations.
     */
    public record CategoryInfo(
            String name,
            String parentName,
            Type type,
            boolean archived,
            List<CategoryInfo> subCategories
    ) {}

    /**
     * Check if CashFlow is in SETUP mode.
     */
    public boolean isInSetupMode() {
        return status == CashFlowStatus.SETUP;
    }

    /**
     * Get all category names (flat list including subcategories).
     */
    public Set<String> getAllCategoryNames() {
        Set<String> names = new java.util.HashSet<>();
        collectCategoryNames(inflowCategories, names);
        collectCategoryNames(outflowCategories, names);
        return names;
    }

    private void collectCategoryNames(List<CategoryInfo> categories, Set<String> names) {
        for (CategoryInfo cat : categories) {
            names.add(cat.name());
            collectCategoryNames(cat.subCategories(), names);
        }
    }

    /**
     * Count total categories (excluding "Uncategorized").
     */
    public int countCategories() {
        return countCategoriesRecursive(inflowCategories) + countCategoriesRecursive(outflowCategories);
    }

    private int countCategoriesRecursive(List<CategoryInfo> categories) {
        int count = 0;
        for (CategoryInfo cat : categories) {
            if (!"Uncategorized".equals(cat.name())) {
                count++;
            }
            count += countCategoriesRecursive(cat.subCategories());
        }
        return count;
    }
}
