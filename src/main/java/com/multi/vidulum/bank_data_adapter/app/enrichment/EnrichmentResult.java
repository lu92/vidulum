package com.multi.vidulum.bank_data_adapter.app.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Final result of enrichment process for all transactions.
 * Aggregates results from all batches.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentResult {

    /**
     * Whether enrichment was applied.
     * False if CSV already had all required fields filled.
     */
    private boolean enrichmentApplied;

    /**
     * Enriched CSV content (with merchant and bankCategory filled).
     */
    private String enrichedCsvContent;

    /**
     * Total number of transactions processed.
     */
    private int totalTransactions;

    /**
     * Number of merchants extracted by AI.
     */
    private int merchantsExtracted;

    /**
     * Number of bankCategories inferred by AI (were empty before).
     */
    private int bankCategoriesInferred;

    /**
     * Number of bankCategories kept from original (were already filled).
     */
    private int bankCategoriesKept;

    /**
     * Number of transactions where enrichment failed and fallback was used.
     */
    private int fallbackCount;

    /**
     * List of all enriched transactions.
     */
    private List<EnrichedTransaction> enrichedTransactions;

    /**
     * Warnings generated during enrichment.
     */
    private List<String> warnings;

    /**
     * Processing time in milliseconds.
     */
    private long processingTimeMs;

    /**
     * Number of AI calls made (batches).
     */
    private int aiCallCount;

    /**
     * Total input tokens used across all batches.
     */
    private int totalInputTokens;

    /**
     * Total output tokens used across all batches.
     */
    private int totalOutputTokens;

    /**
     * Processing notes from AI (aggregated from all batches).
     */
    private String processingNotes;

    /**
     * Creates a "no enrichment needed" result.
     */
    public static EnrichmentResult noEnrichmentNeeded(String csvContent, int rowCount) {
        return EnrichmentResult.builder()
                .enrichmentApplied(false)
                .enrichedCsvContent(csvContent)
                .totalTransactions(rowCount)
                .merchantsExtracted(0)
                .bankCategoriesInferred(0)
                .bankCategoriesKept(rowCount)
                .fallbackCount(0)
                .warnings(List.of())
                .processingTimeMs(0)
                .aiCallCount(0)
                .build();
    }
}
