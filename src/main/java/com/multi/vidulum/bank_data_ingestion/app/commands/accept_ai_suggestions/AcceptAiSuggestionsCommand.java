package com.multi.vidulum.bank_data_ingestion.app.commands.accept_ai_suggestions;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.util.List;

/**
 * Command to accept AI categorization suggestions.
 *
 * This command:
 * 1. Creates new categories in the CashFlow (as per accepted structure)
 * 2. Creates category mappings (bank category → CashFlow category)
 * 3. Saves pattern mappings to user's cache (for future imports)
 * 4. Triggers revalidation of staging session
 *
 * @param cashFlowId the CashFlow ID
 * @param sessionId  the staging session ID
 * @param userId     the user ID (for user-specific pattern cache)
 * @param acceptedCategories categories to create (from AI suggestions)
 * @param acceptedMappings   pattern mappings to apply
 * @param acceptedBankCategoryMappings bank category → CashFlow category mappings
 * @param saveToCache        whether to save accepted mappings to user cache
 */
public record AcceptAiSuggestionsCommand(
        CashFlowId cashFlowId,
        StagingSessionId sessionId,
        String userId,
        List<CategoryToCreate> acceptedCategories,
        List<MappingToApply> acceptedMappings,
        List<BankCategoryMappingToApply> acceptedBankCategoryMappings,
        boolean saveToCache
) implements Command {

    /**
     * A category to create in the CashFlow.
     *
     * @param name      category name
     * @param parentName parent category name (null for top-level)
     * @param type      INFLOW or OUTFLOW
     */
    public record CategoryToCreate(
            String name,
            String parentName,
            Type type
    ) {}

    /**
     * A mapping to apply from bank category to CashFlow category.
     *
     * Note: parentCategory is NOT stored in PatternMapping anymore.
     * The parent relationship is looked up dynamically from the CashFlow
     * to avoid desynchronization when user moves/restructures categories.
     *
     * @param pattern           normalized pattern (e.g., "BIEDRONKA")
     * @param bankCategory      original bank category
     * @param targetCategory    target CashFlow category (leaf category name)
     * @param type              INFLOW or OUTFLOW
     * @param confidence        confidence score (0-100)
     */
    public record MappingToApply(
            String pattern,
            String bankCategory,
            String targetCategory,
            Type type,
            int confidence
    ) {}

    /**
     * A mapping from bank category to CashFlow category.
     * Used when bank category names differ from CashFlow category names.
     *
     * @param bankCategory      the original bank category name (e.g., "Wpływy regularne")
     * @param targetCategory    the target CashFlow category (e.g., "Wynagrodzenie")
     * @param type              INFLOW or OUTFLOW
     * @param confidence        AI confidence score (0-100), nullable for manual mappings
     */
    public record BankCategoryMappingToApply(
            String bankCategory,
            String targetCategory,
            Type type,
            Integer confidence
    ) {}
}
