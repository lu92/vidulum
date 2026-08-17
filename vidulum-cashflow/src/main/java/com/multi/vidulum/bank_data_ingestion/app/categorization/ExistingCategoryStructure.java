package com.multi.vidulum.bank_data_ingestion.app.categorization;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.cashflow.domain.Type;

import java.util.*;

/**
 * Represents the existing category structure in a CashFlow with type information.
 * This structure is passed to AI for proper categorization context.
 *
 * Improvements over flat List<String>:
 * 1. Separates INFLOW from OUTFLOW categories
 * 2. Preserves parent-child hierarchy
 * 3. Provides flat lookup maps for quick validation
 */
public record ExistingCategoryStructure(
        List<CategoryNode> inflowCategories,
        List<CategoryNode> outflowCategories,
        Set<String> allInflowNames,
        Set<String> allOutflowNames
) {

    /**
     * Category node with optional subcategories.
     */
    public record CategoryNode(
            String name,
            List<CategoryNode> subCategories
    ) {
        public CategoryNode(String name) {
            this(name, List.of());
        }

        /**
         * Checks if this category has subcategories.
         */
        public boolean hasSubCategories() {
            return subCategories != null && !subCategories.isEmpty();
        }

        /**
         * Gets all names in this subtree (including self).
         */
        public List<String> getAllNames() {
            List<String> names = new ArrayList<>();
            names.add(name);
            if (subCategories != null) {
                for (CategoryNode sub : subCategories) {
                    names.addAll(sub.getAllNames());
                }
            }
            return names;
        }
    }

    /**
     * Creates an empty structure (no categories).
     */
    public static ExistingCategoryStructure empty() {
        return new ExistingCategoryStructure(
                List.of(),
                List.of(),
                Set.of(),
                Set.of()
        );
    }

    /**
     * Creates structure from CashFlowInfo.
     */
    public static ExistingCategoryStructure fromCashFlowInfo(CashFlowInfo info) {
        if (info == null) {
            return empty();
        }

        List<CategoryNode> inflowNodes = convertCategories(info.inflowCategories());
        List<CategoryNode> outflowNodes = convertCategories(info.outflowCategories());

        Set<String> allInflow = collectAllNames(inflowNodes);
        Set<String> allOutflow = collectAllNames(outflowNodes);

        return new ExistingCategoryStructure(
                inflowNodes,
                outflowNodes,
                allInflow,
                allOutflow
        );
    }

    /**
     * Converts CashFlowInfo.CategoryInfo list to CategoryNode list.
     */
    private static List<CategoryNode> convertCategories(List<CashFlowInfo.CategoryInfo> categories) {
        if (categories == null || categories.isEmpty()) {
            return List.of();
        }

        List<CategoryNode> nodes = new ArrayList<>();
        for (CashFlowInfo.CategoryInfo cat : categories) {
            List<CategoryNode> subNodes = convertCategories(cat.subCategories());
            nodes.add(new CategoryNode(cat.name(), subNodes));
        }
        return nodes;
    }

    /**
     * Collects all category names from a node list.
     */
    private static Set<String> collectAllNames(List<CategoryNode> nodes) {
        Set<String> names = new HashSet<>();
        for (CategoryNode node : nodes) {
            names.addAll(node.getAllNames());
        }
        return names;
    }

    /**
     * Checks if a category name exists for the given type.
     */
    public boolean categoryExists(String categoryName, Type type) {
        if (type == Type.INFLOW) {
            return allInflowNames.contains(categoryName);
        } else {
            return allOutflowNames.contains(categoryName);
        }
    }

    /**
     * Checks if a category name exists (any type).
     */
    public boolean categoryExists(String categoryName) {
        return allInflowNames.contains(categoryName) || allOutflowNames.contains(categoryName);
    }

    /**
     * Gets all category names (flat list for backward compatibility).
     */
    public Set<String> getAllCategoryNames() {
        Set<String> all = new HashSet<>();
        all.addAll(allInflowNames);
        all.addAll(allOutflowNames);
        return all;
    }

    /**
     * Checks if structure has any categories.
     */
    public boolean isEmpty() {
        return inflowCategories.isEmpty() && outflowCategories.isEmpty();
    }

    /**
     * Returns total category count.
     */
    public int totalCategoryCount() {
        return allInflowNames.size() + allOutflowNames.size();
    }

    /**
     * Checks if a category name exists (case-insensitive).
     * Used for Direct Category Match detection.
     */
    public boolean containsCategoryIgnoreCase(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return false;
        }
        String normalized = categoryName.trim().toLowerCase();
        return getAllCategoryNames().stream()
                .anyMatch(name -> name.toLowerCase().equals(normalized));
    }
}
