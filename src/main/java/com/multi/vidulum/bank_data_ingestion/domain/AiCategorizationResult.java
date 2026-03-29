package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.Type;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of AI categorization for a staging session.
 * Contains suggested category structure, pattern mappings, and statistics.
 */
public record AiCategorizationResult(
        StagingSessionId sessionId,
        String status,
        SuggestedStructure suggestedStructure,
        List<PatternSuggestion> patternSuggestions,
        CategorizationStats stats,
        AiCost cost
) {

    public static final String STATUS_AI_SUGGESTIONS_READY = "AI_SUGGESTIONS_READY";
    public static final String STATUS_NO_PATTERNS_TO_CATEGORIZE = "NO_PATTERNS_TO_CATEGORIZE";
    public static final String STATUS_ERROR = "ERROR";

    /**
     * Suggested nested category structure.
     */
    public record SuggestedStructure(
            List<CategoryNode> outflow,
            List<CategoryNode> inflow
    ) {
        public static SuggestedStructure empty() {
            return new SuggestedStructure(List.of(), List.of());
        }
    }

    /**
     * A category node with potential subcategories.
     */
    public record CategoryNode(
            String name,
            List<String> subCategories,
            int transactionCount,
            BigDecimal totalAmount
    ) {
        public CategoryNode(String name, List<String> subCategories) {
            this(name, subCategories, 0, BigDecimal.ZERO);
        }
    }

    /**
     * A pattern suggestion with confidence and source information.
     */
    public record PatternSuggestion(
            String pattern,
            String sampleTransaction,
            String suggestedCategory,
            String parentCategory,
            Type type,
            int confidence,
            PatternSource source,
            int transactionCount,
            BigDecimal totalAmount,
            boolean needsUserInput
    ) {
        /**
         * Creates a suggestion from a cached pattern mapping.
         */
        public static PatternSuggestion fromCache(
                PatternMapping mapping,
                String sampleTransaction,
                int transactionCount,
                BigDecimal totalAmount
        ) {
            PatternSource displaySource = mapping.source() == PatternSource.GLOBAL
                    ? PatternSource.GLOBAL
                    : PatternSource.USER;

            return new PatternSuggestion(
                    mapping.normalizedPattern(),
                    sampleTransaction,
                    mapping.suggestedCategory(),
                    mapping.parentCategory(),
                    mapping.categoryType(),
                    mapping.confidencePercentage(),
                    displaySource,
                    transactionCount,
                    totalAmount,
                    false // cached patterns don't need user input
            );
        }

        /**
         * Creates a suggestion from AI response.
         */
        public static PatternSuggestion fromAi(
                String pattern,
                String sampleTransaction,
                String category,
                String parentCategory,
                Type type,
                int confidence,
                int transactionCount,
                BigDecimal totalAmount
        ) {
            return new PatternSuggestion(
                    pattern,
                    sampleTransaction,
                    category,
                    parentCategory,
                    type,
                    confidence,
                    PatternSource.AI,
                    transactionCount,
                    totalAmount,
                    confidence < 50 // needs user input if low confidence
            );
        }

        /**
         * Checks if this suggestion is auto-accepted (high confidence).
         */
        public boolean isAutoAccepted() {
            return confidence >= 90;
        }

        /**
         * Checks if this suggestion needs confirmation (medium confidence).
         */
        public boolean needsConfirmation() {
            return confidence >= 50 && confidence < 90;
        }
    }

    /**
     * Statistics about the categorization process.
     */
    public record CategorizationStats(
            int totalPatterns,
            int autoAccepted,
            int suggested,
            int needsManual,
            int fromGlobalCache,
            int fromUserCache,
            int fromAi
    ) {
        public static CategorizationStats empty() {
            return new CategorizationStats(0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Cost information for AI categorization.
     */
    public record AiCost(
            int tokensUsed,
            String estimatedCost
    ) {
        public static AiCost free() {
            return new AiCost(0, "FREE");
        }

        public static AiCost estimated(int tokens) {
            // Rough estimate: $0.01 per 1000 tokens
            double cost = tokens * 0.00001;
            return new AiCost(tokens, String.format("%.2f USD", cost));
        }
    }

    /**
     * Creates a successful result with suggestions.
     */
    public static AiCategorizationResult success(
            StagingSessionId sessionId,
            SuggestedStructure structure,
            List<PatternSuggestion> suggestions,
            CategorizationStats stats,
            AiCost cost
    ) {
        return new AiCategorizationResult(
                sessionId,
                STATUS_AI_SUGGESTIONS_READY,
                structure,
                suggestions,
                stats,
                cost
        );
    }

    /**
     * Creates a result when there are no patterns to categorize.
     */
    public static AiCategorizationResult noPatterns(StagingSessionId sessionId) {
        return new AiCategorizationResult(
                sessionId,
                STATUS_NO_PATTERNS_TO_CATEGORIZE,
                SuggestedStructure.empty(),
                List.of(),
                CategorizationStats.empty(),
                AiCost.free()
        );
    }

    /**
     * Creates an error result.
     */
    public static AiCategorizationResult error(StagingSessionId sessionId, String errorMessage) {
        return new AiCategorizationResult(
                sessionId,
                STATUS_ERROR,
                SuggestedStructure.empty(),
                List.of(),
                CategorizationStats.empty(),
                AiCost.free()
        );
    }
}
