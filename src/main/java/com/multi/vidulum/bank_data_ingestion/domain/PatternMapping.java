package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;

import java.time.Instant;

/**
 * Domain record representing a pattern-to-category mapping.
 *
 * Pattern mappings are used to cache categorization decisions:
 * - GLOBAL patterns: Known brands/institutions (BIEDRONKA, ZUS, NETFLIX) - currently disabled
 * - USER patterns: User's previous categorizations, isolated per CashFlow
 * - AI patterns: AI-suggested categorizations
 *
 * This enables:
 * - Fast lookups for known patterns (FREE)
 * - Learning from user confirmations
 * - Reducing AI calls on subsequent imports
 *
 * The {@code intendedParentCategory} field stores AI's original intent for hierarchy.
 * This is a HINT for future AI calls - not a hard reference.
 * The actual parent is looked up dynamically from CashFlow to handle user reorganizations.
 */
public record PatternMapping(
        PatternMappingId id,
        String normalizedPattern,        // e.g., "ZUS", "BIEDRONKA"
        String suggestedCategory,        // e.g., "Social Security"
        String intendedParentCategory,   // AI's intended parent (nullable) - hint for future imports
        Type categoryType,               // INFLOW or OUTFLOW
        PatternSource source,            // GLOBAL, USER, or AI
        String userId,                   // null for GLOBAL patterns
        String cashFlowId,               // null for GLOBAL patterns, required for USER
        int usageCount,
        double confidenceScore,          // 0.0 - 1.0
        Instant createdAt,
        Instant lastUsedAt
) {

    /**
     * Creates a new GLOBAL pattern mapping.
     * Global patterns are known brands/institutions available to all users.
     * NOTE: GLOBAL patterns are currently disabled but kept for future use.
     */
    public static PatternMapping createGlobal(
            String normalizedPattern,
            String suggestedCategory,
            String intendedParentCategory,
            Type categoryType,
            double confidenceScore
    ) {
        Instant now = Instant.now();
        return new PatternMapping(
                PatternMappingId.generate(),
                normalizedPattern.toUpperCase().trim(),
                suggestedCategory,
                intendedParentCategory,
                categoryType,
                PatternSource.GLOBAL,
                null,
                null,  // no cashFlowId for GLOBAL
                0,
                confidenceScore,
                now,
                now
        );
    }

    /**
     * Creates a new USER pattern mapping.
     * User patterns are learned from user's categorization confirmations.
     * Isolated per CashFlow to avoid cross-CashFlow category mismatches.
     */
    public static PatternMapping createUser(
            String normalizedPattern,
            String suggestedCategory,
            String intendedParentCategory,
            Type categoryType,
            String userId,
            String cashFlowId,
            double confidenceScore
    ) {
        Instant now = Instant.now();
        return new PatternMapping(
                PatternMappingId.generate(),
                normalizedPattern.toUpperCase().trim(),
                suggestedCategory,
                intendedParentCategory,
                categoryType,
                PatternSource.USER,
                userId,
                cashFlowId,
                1,
                confidenceScore,
                now,
                now
        );
    }

    /**
     * Creates a new AI pattern mapping.
     * AI patterns are suggestions from the AI categorization service.
     * Isolated per CashFlow.
     */
    public static PatternMapping createAi(
            String normalizedPattern,
            String suggestedCategory,
            String intendedParentCategory,
            Type categoryType,
            String userId,
            String cashFlowId,
            double confidenceScore
    ) {
        Instant now = Instant.now();
        return new PatternMapping(
                PatternMappingId.generate(),
                normalizedPattern.toUpperCase().trim(),
                suggestedCategory,
                intendedParentCategory,
                categoryType,
                PatternSource.AI,
                userId,
                cashFlowId,
                0,
                confidenceScore,
                now,
                now
        );
    }

    /**
     * Records a usage of this pattern, incrementing the counter and updating timestamp.
     */
    public PatternMapping recordUsage() {
        return new PatternMapping(
                id,
                normalizedPattern,
                suggestedCategory,
                intendedParentCategory,
                categoryType,
                source,
                userId,
                cashFlowId,
                usageCount + 1,
                confidenceScore,
                createdAt,
                Instant.now()
        );
    }

    /**
     * Updates the category suggestion for this pattern.
     */
    public PatternMapping updateCategory(String newCategory, String newIntendedParent, double newConfidence) {
        return new PatternMapping(
                id,
                normalizedPattern,
                newCategory,
                newIntendedParent,
                categoryType,
                source,
                userId,
                cashFlowId,
                usageCount,
                newConfidence,
                createdAt,
                Instant.now()
        );
    }

    /**
     * Converts confidence score (0.0-1.0) to percentage (0-100).
     */
    public int confidencePercentage() {
        return (int) Math.round(confidenceScore * 100);
    }

    /**
     * Checks if this pattern has high confidence (auto-accept threshold).
     */
    public boolean isHighConfidence() {
        return confidenceScore >= 0.90;
    }

    /**
     * Checks if this pattern needs user confirmation (medium confidence).
     */
    public boolean needsConfirmation() {
        return confidenceScore >= 0.50 && confidenceScore < 0.90;
    }

    /**
     * Checks if this pattern requires manual input (low confidence).
     */
    public boolean needsManualInput() {
        return confidenceScore < 0.50;
    }
}
