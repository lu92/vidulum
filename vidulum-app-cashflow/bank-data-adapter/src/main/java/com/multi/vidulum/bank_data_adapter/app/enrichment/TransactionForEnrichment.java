package com.multi.vidulum.bank_data_adapter.app.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a transaction prepared for enrichment.
 * Contains only fields needed by AI for merchant extraction and category inference.
 *
 * Note: amount and type are NOT included as they don't help with merchant extraction
 * or category inference - those are determined from name and description only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionForEnrichment {

    /**
     * Row index from CSV (0-based, after header).
     */
    private int rowIndex;

    /**
     * Transaction name (primary identifier).
     * Examples: "ŻABKA POLSKA 4521 WARSZAWA", "Przelew od Jan Kowalski"
     */
    private String name;

    /**
     * Additional description.
     * May contain merchant info, reference numbers, etc.
     */
    private String description;

    /**
     * Original bank category (may be empty for some banks like Nest).
     * If non-empty, AI should keep it unchanged.
     */
    private String bankCategory;

    /**
     * Returns true if bankCategory is empty or null.
     * AI should infer category only for these transactions.
     */
    public boolean needsBankCategoryInference() {
        return bankCategory == null || bankCategory.isBlank();
    }
}
