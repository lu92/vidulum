package com.multi.vidulum.bank_data_adapter.app.enrichment;

import com.multi.vidulum.bank_data_adapter.domain.TransactionClassification;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single transaction after enrichment by AI.
 * Contains extracted merchant, classification, and optionally inferred bankCategory.
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
     * Transaction classification determined by AI.
     * Indicates the type of transaction: MERCHANT, BANK_FEE, CASH_WITHDRAWAL, etc.
     */
    private TransactionClassification classification;

    /**
     * Extracted merchant name (normalized, uppercase).
     * Examples: "ŻABKA", "NETFLIX", "JAN KOWALSKI", "ZUS"
     * Null for non-merchant transactions (BANK_FEE, CASH_WITHDRAWAL, etc.)
     */
    private String merchant;

    /**
     * Confidence score for merchant extraction (0.0 to 1.0).
     * - 0.95+ = exact business name found
     * - 0.8-0.95 = clear company/person name extracted
     * - 0.5-0.8 = inferred from context
     * - <0.5 = uncertain, used fallback
     * Null for non-merchant transactions.
     */
    private Double merchantConfidence;

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
     * Reason why AI chose this classification.
     * Useful for debugging and understanding AI decisions.
     * Examples:
     * - "Card payment at grocery store"
     * - "Express transfer commission - bank internal charge"
     * - "ATM terminal code pattern detected"
     */
    private String classificationReason;

    /**
     * Location extracted from transaction (for ATM, physical locations).
     * Examples: "WARSZAWA", "KRAKÓW", "UL. MARSZAŁKOWSKA 10"
     */
    private String location;

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

    /**
     * Returns effective classification (UNKNOWN if null).
     */
    public TransactionClassification effectiveClassification() {
        return classification != null ? classification : TransactionClassification.UNKNOWN;
    }

    /**
     * Whether this transaction has a meaningful merchant.
     */
    public boolean hasMerchant() {
        return merchant != null && !merchant.isBlank() && effectiveClassification().hasMerchant();
    }

    /**
     * Whether this transaction should be auto-categorized (skip AI categorization).
     */
    public boolean isAutoCategorizeable() {
        return effectiveClassification().isAutoCategorizeable();
    }
}
