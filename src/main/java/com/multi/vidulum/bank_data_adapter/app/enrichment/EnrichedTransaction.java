package com.multi.vidulum.bank_data_adapter.app.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single transaction after enrichment by AI.
 * Contains extracted merchant and optionally inferred bankCategory.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichedTransaction {

    /**
     * Row index from original CSV (0-based).
     * Used to match enrichment result back to original transaction.
     */
    private int rowIndex;

    /**
     * Extracted merchant name (normalized, uppercase).
     * Examples: "ŻABKA", "NETFLIX", "JAN KOWALSKI", "ZUS"
     */
    private String merchant;

    /**
     * Confidence score for merchant extraction (0.0 to 1.0).
     * - 0.95+ = exact business name found
     * - 0.8-0.95 = clear company/person name extracted
     * - 0.5-0.8 = inferred from context
     * - <0.5 = uncertain, used fallback
     */
    private double merchantConfidence;

    /**
     * Bank category for the transaction.
     * Either original (if non-empty) or AI-inferred.
     */
    private String bankCategory;

    /**
     * Source of bankCategory value.
     */
    private BankCategorySource bankCategorySource;

    /**
     * Indicates where the bankCategory value came from.
     */
    public enum BankCategorySource {
        /**
         * Original value from bank CSV (e.g., Pekao).
         * AI did not modify this value.
         */
        ORIGINAL,

        /**
         * AI inferred the category from transaction context.
         * Original value was empty (e.g., Nest Bank).
         */
        AI_INFERRED,

        /**
         * AI could not determine category, used fallback ("Inne").
         */
        AI_FALLBACK,

        /**
         * Error during enrichment, used system default.
         */
        FALLBACK_ERROR
    }
}
