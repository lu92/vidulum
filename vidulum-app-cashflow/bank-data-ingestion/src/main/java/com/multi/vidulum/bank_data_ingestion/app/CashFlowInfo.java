package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.cashflow.api.Type;

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
        String userId,
        CashFlowStatus status,
        YearMonth activePeriod,
        YearMonth startPeriod,
        List<CategoryInfo> inflowCategories,
        List<CategoryInfo> outflowCategories,
        // existingTransactionIds is gone: CashFlowModuleApi.alreadyImported answers that question
        // properly now, with the cash change ids this record could never carry. The field had no
        // reader in production - it had none for a long time either, because the HTTP client filled
        // it with an empty set and the test double with identifiers from another namespace.
        int cashChangesCount
        // monthStatuses is gone too, and for a sharper reason than the field above: it was never
        // cashflow's to answer. Month statuses belong to cashflow_forecast_processor, so one
        // signature was merging two modules' state and the HTTP client underneath was doing two
        // requests - the second of them to this application's own endpoint, over loopback.
        // CashFlowForecastApi answers it now, and MonthStatuses carries the rule with the data.
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
     * A category name qualified by its flow type (INFLOW/OUTFLOW).
     * <p>
     * In a CashFlow, "Inne" as OUTFLOW and "Inne" as INFLOW are <b>separate categories</b>
     * — each requires its own {@code CategoryCreatedEvent}. A bare {@code String} name
     * is ambiguous when the same name exists in both flow types. This record makes the
     * distinction explicit and prevents bugs where a category existing in one type
     * is mistakenly treated as existing in the other.
     *
     * @see #getAllTypedCategoryKeys()
     */
    public record TypedCategoryKey(String name, Type type) {}

    /**
     * Get all category keys qualified by type (INFLOW/OUTFLOW), including subcategories.
     * <p>
     * Use this instead of {@link #getAllCategoryNames()} when you need to know whether
     * a category exists <b>for a specific type</b>.
     */
    public Set<TypedCategoryKey> getAllTypedCategoryKeys() {
        Set<TypedCategoryKey> keys = new java.util.HashSet<>();
        collectTypedCategoryKeys(inflowCategories, Type.INFLOW, keys);
        collectTypedCategoryKeys(outflowCategories, Type.OUTFLOW, keys);
        return keys;
    }

    private void collectTypedCategoryKeys(List<CategoryInfo> categories, Type type, Set<TypedCategoryKey> keys) {
        for (CategoryInfo cat : categories) {
            keys.add(new TypedCategoryKey(cat.name(), type));
            collectTypedCategoryKeys(cat.subCategories(), type, keys);
        }
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
     * Find category name with case-insensitive matching.
     * Returns the actual category name if found, empty otherwise.
     * This is used for Priority 0: Direct Category Match.
     */
    public java.util.Optional<String> findCategoryNameIgnoreCase(String bankCategory) {
        if (bankCategory == null || bankCategory.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalized = bankCategory.trim().toLowerCase();
        return getAllCategoryNames().stream()
                .filter(name -> name.toLowerCase().equals(normalized))
                .findFirst();
    }

    /**
     * Find category name with case-insensitive matching, respecting type (INFLOW/OUTFLOW).
     * Returns the actual category name if found in the correct type, empty otherwise.
     */
    public java.util.Optional<String> findCategoryNameIgnoreCase(String bankCategory, Type type) {
        if (bankCategory == null || bankCategory.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalized = bankCategory.trim().toLowerCase();
        List<CategoryInfo> categories = type == Type.INFLOW ? inflowCategories : outflowCategories;
        Set<String> typeSpecificNames = new java.util.HashSet<>();
        collectCategoryNames(categories, typeSpecificNames);
        return typeSpecificNames.stream()
                .filter(name -> name.toLowerCase().equals(normalized))
                .findFirst();
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
     * Finds the parent category for a given category name and type (case-insensitive).
     * Returns null if the category is a top-level category or not found.
     *
     * This is used for pattern matching where we need to determine the parent
     * category dynamically from the CashFlow structure.
     */
    public CategoryName findParentCategory(String categoryName, Type type) {
        List<CategoryInfo> categories = type == Type.INFLOW ? inflowCategories : outflowCategories;
        return findParentCategoryRecursive(categoryName.toLowerCase(), categories, null);
    }

    private CategoryName findParentCategoryRecursive(
            String normalizedCategoryName,
            List<CategoryInfo> categories,
            String currentParent) {

        for (CategoryInfo cat : categories) {
            // Check if this category matches (case-insensitive)
            if (cat.name().toLowerCase().equals(normalizedCategoryName)) {
                return currentParent != null ? new CategoryName(currentParent) : null;
            }
            // Check subcategories
            CategoryName found = findParentCategoryRecursive(normalizedCategoryName, cat.subCategories(), cat.name());
            if (found != null || (cat.subCategories().stream().anyMatch(sub -> sub.name().toLowerCase().equals(normalizedCategoryName)))) {
                // If found in subcategories, return the parent
                if (cat.subCategories().stream().anyMatch(sub -> sub.name().toLowerCase().equals(normalizedCategoryName))) {
                    return new CategoryName(cat.name());
                }
                return found;
            }
        }
        return null;
    }
}
