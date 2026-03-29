package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
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
        int cashChangesCount,
        Map<YearMonth, MonthStatus> monthStatuses
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
     * Month status enum - mirrors CashFlowMonthlyForecast.Status.
     */
    public enum MonthStatus {
        IMPORT_PENDING,
        IMPORTED,
        ROLLED_OVER,
        ATTESTED,
        ACTIVE,
        FORECASTED
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
     * Check if CashFlow is in OPEN mode.
     */
    public boolean isInOpenMode() {
        return status == CashFlowStatus.OPEN;
    }

    /**
     * Check if CashFlow is in CLOSED mode.
     */
    public boolean isInClosedMode() {
        return status == CashFlowStatus.CLOSED;
    }

    /**
     * Get the status of a specific month.
     * Returns null if the month is not in the forecast range.
     */
    public MonthStatus getMonthStatus(YearMonth month) {
        if (monthStatuses == null) {
            return null;
        }
        return monthStatuses.get(month);
    }

    /**
     * Check if a month allows importing transactions (gap filling or ongoing sync).
     * <p>
     * Allowed for:
     * - IMPORT_PENDING (historical backfill in SETUP mode)
     * - IMPORTED (gap filling after attestation)
     * - ROLLED_OVER (gap filling after rollover)
     * - ACTIVE (ongoing sync)
     * <p>
     * NOT allowed for:
     * - FORECASTED (future months - cannot import)
     * - ATTESTED (deprecated, treated as read-only)
     */
    public boolean isMonthImportAllowed(YearMonth month) {
        MonthStatus monthStatus = getMonthStatus(month);
        if (monthStatus == null) {
            return false;
        }
        return switch (monthStatus) {
            case IMPORT_PENDING, IMPORTED, ROLLED_OVER, ACTIVE -> true;
            case FORECASTED, ATTESTED -> false;
        };
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

    /**
     * Finds the parent category for a given category name and type.
     * Returns null if the category is a top-level category or not found.
     *
     * This is used for pattern matching where we need to determine the parent
     * category dynamically from the CashFlow structure.
     */
    public CategoryName findParentCategory(String categoryName, Type type) {
        List<CategoryInfo> categories = type == Type.INFLOW ? inflowCategories : outflowCategories;
        return findParentCategoryRecursive(categoryName, categories, null);
    }

    private CategoryName findParentCategoryRecursive(
            String categoryName,
            List<CategoryInfo> categories,
            String currentParent) {

        for (CategoryInfo cat : categories) {
            // Check if this category matches
            if (cat.name().equals(categoryName)) {
                return currentParent != null ? new CategoryName(currentParent) : null;
            }
            // Check subcategories
            CategoryName found = findParentCategoryRecursive(categoryName, cat.subCategories(), cat.name());
            if (found != null || (cat.subCategories().stream().anyMatch(sub -> sub.name().equals(categoryName)))) {
                // If found in subcategories, return the parent
                if (cat.subCategories().stream().anyMatch(sub -> sub.name().equals(categoryName))) {
                    return new CategoryName(cat.name());
                }
                return found;
            }
        }
        return null;
    }
}
