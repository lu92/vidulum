package com.multi.vidulum.bank_data_ingestion.classification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.multi.vidulum.bank_data_ingestion.canonical.PaymentMethod;
import com.multi.vidulum.bank_data_ingestion.canonical.TransactionClassification;
import com.multi.vidulum.cashflow.api.Type;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * The HTTP shapes of classification: the suggestions and the acceptance.

 * <p>The {@code Ai} prefixes are the supplier's name rather than the capability's, and they stay
 * until step 1 of F67 renames the inside of this section. The endpoints keep their paths regardless,
 * by decision of the code owner.
 *
 * <p>Split out of the single 779-line {@code BankDataIngestionDto}, which was the last thing
 * left in the {@code app} package - and the last reason a section had to depend on it. That
 * package is gone now: every file in this module belongs to a named section.
 */
public class ClassificationDto {

    // ============ AI Categorization DTOs ============

    /**
     * Response from AI categorization endpoint.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClassifyResponse {
        private String sessionId;
        private String status;
        private SuggestedStructureJson suggestedStructure;
        private List<PatternSuggestionJson> patternSuggestions;
        private List<BankCategorySuggestionJson> bankCategorySuggestions;
        private List<AutoCategorizableSuggestionJson> autoCategorizableSuggestions;
        private List<ContextMappingJson> contextMappings;
        private List<BankCategoryFallbackJson> bankCategoryFallbacks;
        private ClassificationStatsJson stats;
        private ClassificationCostJson cost;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedStructureJson {
        private List<CategoryNodeJson> outflow;
        private List<CategoryNodeJson> inflow;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryNodeJson {
        private String name;
        private List<String> subCategories;
        private int transactionCount;
        private double totalAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PatternSuggestionJson {
        private String pattern;
        private String sampleTransaction;
        private String suggestedCategory;
        private String parentCategory;
        private Type type;
        private int confidence;
        private String source; // GLOBAL, USER, AI
        private int transactionCount;
        private double totalAmount;
        private boolean needsUserInput;
    }

    /**
     * Bank category suggestion - maps bank category to CashFlow category.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankCategorySuggestionJson {
        private String bankCategory;
        private String targetCategory;
        private String parentCategory;
        private Type type;
        private int confidence;
        private int transactionCount;
        private double totalAmount;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClassificationStatsJson {
        private int totalPatterns;
        private int autoAccepted;
        private int suggested;
        private int needsManual;
        private int fromGlobalCache;
        private int fromUserCache;
        private int fromAi;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClassificationCostJson {
        private int tokensUsed;
        private String estimatedCost;
    }

    /**
     * Auto-categorizable transaction suggestion (BANK_FEE, SELF_TRANSFER, etc.)
     * These are pre-filtered transactions that don't need AI processing.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AutoCategorizableSuggestionJson {
        private String classification;      // BANK_FEE, CASH_WITHDRAWAL, etc.
        private String suggestedCategory;   // Opłaty bankowe, Wypłaty gotówkowe, etc.
        private String parentCategory;      // Zarządzanie kontem
        private Type type;
        private int transactionCount;
        private double totalAmount;
        private List<String> sampleTransactions;
    }

    /**
     * Context mapping from three-signal categorization.
     * Maps a (merchant, bankCategory) pair to a target category.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContextMappingJson {
        private String pattern;
        private String bankCategory;
        private String suggestedCategory;
        private String parentCategory;
        private Type type;
        private int confidence;
        private String dominantSignal;
        private boolean isExistingCategory;
        private String reason;
        private int transactionCount;
        private double totalAmount;
    }

    /**
     * Bank category fallback — default mapping for a semantic bankCategory.
     * Used when transaction has a known bankCategory but unknown merchant.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankCategoryFallbackJson {
        private String bankCategory;
        private String defaultTarget;
        private String parentCategory;
        private Type type;
        private int confidence;
        private String reason;
    }

    /**
     * Request to accept AI categorization suggestions.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AcceptClassificationRequest {
        private List<CategoryToCreateJson> acceptedCategories;
        private List<MappingToApplyJson> acceptedMappings;
        private List<BankCategoryMappingToApplyJson> acceptedBankCategoryMappings;
        private boolean saveToCache;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryToCreateJson {
        private String name;
        private String parentName;
        private Type type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MappingToApplyJson {
        private String pattern;
        private String bankCategory;
        private String targetCategory;
        private String parentCategory;
        private Type type;
        private int confidence;
    }

    /**
     * Bank category mapping - maps bank category name to CashFlow category.
     * Used when bank category names differ from CashFlow category names.
     */
    /**
     * Bank category mapping from AI suggestions.
     * Maps bank-assigned categories (like "PRZELEW") to CashFlow categories (like "Wynagrodzenie").
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BankCategoryMappingToApplyJson {
        private String bankCategory;
        private String targetCategory;
        private String parentCategory;
        private Type type;
        /** AI confidence score (0-100). Optional - used for analytics/auditing. */
        private Integer confidence;
    }

    /**
     * Response from accepting AI suggestions.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AcceptClassificationResponse {
        private String cashFlowId;
        private String sessionId;
        private String status;
        private int categoriesCreated;
        private int mappingsApplied;
        private int patternsCached;
        private List<String> warnings;
        private StagingValidationSummaryJson validationSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StagingValidationSummaryJson {
        private int totalTransactions;
        private int validTransactions;
        private int invalidTransactions;
        private int duplicateTransactions;
        private boolean readyForImport;
    }
}
