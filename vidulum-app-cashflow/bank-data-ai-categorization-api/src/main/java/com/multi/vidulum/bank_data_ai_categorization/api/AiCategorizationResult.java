package com.multi.vidulum.bank_data_ai_categorization.api;

import com.multi.vidulum.cashflow.api.Type;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of AI categorization for a staging session.
 * Contains suggested category structure, pattern mappings, and statistics.
 *
 * <p><b>{@code sessionId} is a String, not a typed id.</b> It used to be ingestion's
 * {@code StagingSessionId} - which is the sort of thing that stops a contract being a contract: the
 * engine never looks at it, it echoes it back so the caller can correlate. Opaque is the honest
 * shape.
 *
 * <p>Likewise {@code PatternSuggestion.fromCache} takes the values of a remembered pattern rather
 * than a pattern document: what the cache is stored in belongs to whoever owns the cache.
 */
public record AiCategorizationResult(
        String sessionId,
        String status,
        SuggestedStructure suggestedStructure,
        List<PatternSuggestion> patternSuggestions,
        List<BankCategorySuggestion> bankCategorySuggestions,
        List<UnrecognizedPattern> unrecognizedPatterns,
        List<StructureOptimization> structureOptimizations,
        List<AutoCategorizableSuggestion> autoCategorizableSuggestions,
        List<ContextMapping> contextMappings,
        List<BankCategoryFallback> bankCategoryFallbacks,
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
     * A pattern that AI could not recognize.
     */
    public record UnrecognizedPattern(
            String pattern,
            Type type,
            String reason,
            int transactionCount,
            BigDecimal totalAmount
    ) {}

    /**
     * Suggested structure optimization from AI.
     * Used when AI detects patterns from cache that had hierarchy intents
     * and can suggest reorganizing categories.
     *
     * Example: If cached pattern "ZABKA" had intendedParent="Żywność", but
     * current CashFlow has "Zakupy spożywcze" as root (flattened), AI can suggest
     * creating "Żywność" parent and moving "Zakupy spożywcze" under it.
     */
    public record StructureOptimization(
            String categoryName,          // Category to move/reorganize
            String suggestedParent,       // Suggested new parent
            String currentParent,         // Current parent (null if root)
            Type type,                    // INFLOW or OUTFLOW
            int affectedTransactionCount, // How many transactions would be affected
            String reason                 // Why AI suggests this optimization
    ) {}

    /**
     * A suggestion to map a bank category to a CashFlow category.
     * Used when bank category names differ from CashFlow category names.
     */
    public record BankCategorySuggestion(
            String bankCategory,
            String targetCategory,
            String parentCategory,
            Type type,
            int confidence,
            int transactionCount,
            BigDecimal totalAmount,
            String reason
    ) {
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
            boolean needsUserInput,
            boolean isExistingCategory,
            String reason
    ) {
        /**
         * Creates a suggestion from a cached pattern mapping.
         * Uses intendedParentCategory from cache as hint for AI to maintain hierarchy consistency.
         */
        public static PatternSuggestion fromCache(
                String normalizedPattern,
                String suggestedCategory,
                String intendedParentCategory,
                Type categoryType,
                int confidencePercentage,
                PatternSource source,
                String sampleTransaction,
                int transactionCount,
                BigDecimal totalAmount
        ) {
            PatternSource displaySource = source == PatternSource.GLOBAL
                    ? PatternSource.GLOBAL
                    : PatternSource.USER;

            return new PatternSuggestion(
                    normalizedPattern,
                    sampleTransaction,
                    suggestedCategory,
                    intendedParentCategory,  // Use cached hierarchy intent as hint
                    categoryType,
                    confidencePercentage,
                    displaySource,
                    transactionCount,
                    totalAmount,
                    false, // cached patterns don't need user input
                    true,  // cached patterns always map to existing categories
                    "Pattern from cache"
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
                BigDecimal totalAmount,
                boolean isExistingCategory,
                String reason
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
                    confidence < 50, // needs user input if low confidence
                    isExistingCategory,
                    reason
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
     * Suggestion for auto-categorizable transactions (BANK_FEE, SELF_TRANSFER, etc.)
     * that don't need AI processing. These are pre-filtered based on the transaction's classification word
     * and mapped to "Zarządzanie kontem" parent category with classification-specific subcategories.
     */
    /**
     * A group the engine recognised without asking the model - bank fees, cash withdrawals and the
     * like. {@code classification} is the vocabulary <em>word</em> rather than an enum, which is the
     * same text contract the adapter and ingestion already use between themselves: a producer is
     * allowed to know words this module has no policy for, and those simply fall through to the AI.
     */
    public record AutoCategorizableSuggestion(
            String classification,
            String suggestedCategory,         // e.g., "Opłaty bankowe"
            String parentCategory,            // "Zarządzanie kontem"
            Type type,
            int transactionCount,
            BigDecimal totalAmount,
            List<String> sampleTransactions   // First few transaction names for context
    ) {
        /**
         * Maps classification to suggested subcategory name (Polish).
         */
        public static String categoryForClassification(String classification) {
            if (classification == null) {
                return null;
            }
            return switch (classification) {
                case "BANK_FEE" -> "Opłaty bankowe";
                case "CASH_WITHDRAWAL" -> "Wypłaty gotówkowe";
                case "CASH_DEPOSIT" -> "Wpłaty gotówkowe";
                case "SELF_TRANSFER" -> "Przelewy własne";
                case "INTEREST" -> "Odsetki";
                default -> null;   // a word this module has no policy for falls through to the AI
            };
        }

        /**
         * Default parent category for all auto-categorizable transactions.
         */
        public static final String PARENT_CATEGORY = "Zarządzanie kontem";
    }

    /**
     * Unified mapping for a (merchant, bankCategory) context pair.
     * Each group in the AI prompt is a unique (pattern, bankCategory) combination,
     * so the AI produces one contextMapping per group.
     */
    public record ContextMapping(
            String pattern,
            String bankCategory,
            String suggestedCategory,
            String parentCategory,
            Type type,
            int confidence,
            String dominantSignal,
            boolean isExistingCategory,
            String reason,
            int transactionCount,
            BigDecimal totalAmount
    ) {
        public boolean isAutoAccepted() { return confidence >= 90; }
        public boolean needsConfirmation() { return confidence >= 50 && confidence < 90; }
    }

    /**
     * Default fallback mapping for a semantic bankCategory.
     * Used when a transaction has a known bankCategory but unknown/weak merchant.
     * AI MUST generate one fallback per semantic bankCategory (skipping generic ones like "Inne").
     */
    public record BankCategoryFallback(
            String bankCategory,
            String defaultTarget,
            String parentCategory,
            Type type,
            int confidence,
            String reason
    ) {}

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
            int fromAi,
            int matchedExisting,
            int createdNew,
            int unrecognized
    ) {
        public static CategorizationStats empty() {
            return new CategorizationStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
            String sessionId,
            SuggestedStructure structure,
            List<PatternSuggestion> suggestions,
            List<BankCategorySuggestion> bankCategorySuggestions,
            List<UnrecognizedPattern> unrecognizedPatterns,
            List<StructureOptimization> structureOptimizations,
            List<AutoCategorizableSuggestion> autoCategorizableSuggestions,
            List<ContextMapping> contextMappings,
            List<BankCategoryFallback> bankCategoryFallbacks,
            CategorizationStats stats,
            AiCost cost
    ) {
        return new AiCategorizationResult(
                sessionId,
                STATUS_AI_SUGGESTIONS_READY,
                structure,
                suggestions,
                bankCategorySuggestions,
                unrecognizedPatterns,
                structureOptimizations,
                autoCategorizableSuggestions,
                contextMappings,
                bankCategoryFallbacks,
                stats,
                cost
        );
    }

    /**
     * Creates a result when there are no patterns to categorize.
     */
    public static AiCategorizationResult noPatterns(String sessionId) {
        return new AiCategorizationResult(
                sessionId,
                STATUS_NO_PATTERNS_TO_CATEGORIZE,
                SuggestedStructure.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CategorizationStats.empty(),
                AiCost.free()
        );
    }

    /**
     * Creates an error result.
     */
    public static AiCategorizationResult error(String sessionId, String errorMessage) {
        return new AiCategorizationResult(
                sessionId,
                STATUS_ERROR,
                SuggestedStructure.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CategorizationStats.empty(),
                AiCost.free()
        );
    }
}
