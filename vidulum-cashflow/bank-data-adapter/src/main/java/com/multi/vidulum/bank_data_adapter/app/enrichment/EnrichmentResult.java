package com.multi.vidulum.bank_data_adapter.app.enrichment;

import com.multi.vidulum.bank_data_adapter.domain.TransactionClassification;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

    // ========== CLASSIFICATION BREAKDOWN ==========

    /**
     * Number of transactions classified as MERCHANT.
     */
    private int classificationMerchantCount;

    /**
     * Number of transactions classified as BANK_FEE.
     */
    private int classificationBankFeeCount;

    /**
     * Number of transactions classified as CASH_WITHDRAWAL.
     */
    private int classificationCashWithdrawalCount;

    /**
     * Number of transactions classified as CASH_DEPOSIT.
     */
    private int classificationCashDepositCount;

    /**
     * Number of transactions classified as SELF_TRANSFER.
     */
    private int classificationSelfTransferCount;

    /**
     * Number of transactions classified as INTEREST.
     */
    private int classificationInterestCount;

    /**
     * Number of transactions classified as UNKNOWN.
     */
    private int classificationUnknownCount;

    // ========== CONFIDENCE BREAKDOWN ==========

    /**
     * Number of transactions with high confidence (>= 0.8).
     */
    private int highConfidenceCount;

    /**
     * Number of transactions with medium confidence (0.5 - 0.8).
     */
    private int mediumConfidenceCount;

    /**
     * Number of transactions with low confidence (< 0.5).
     */
    private int lowConfidenceCount;

    /**
     * Returns classification breakdown as a map.
     */
    public Map<TransactionClassification, Integer> getClassificationBreakdown() {
        Map<TransactionClassification, Integer> breakdown = new EnumMap<>(TransactionClassification.class);
        breakdown.put(TransactionClassification.MERCHANT, classificationMerchantCount);
        breakdown.put(TransactionClassification.BANK_FEE, classificationBankFeeCount);
        breakdown.put(TransactionClassification.CASH_WITHDRAWAL, classificationCashWithdrawalCount);
        breakdown.put(TransactionClassification.CASH_DEPOSIT, classificationCashDepositCount);
        breakdown.put(TransactionClassification.SELF_TRANSFER, classificationSelfTransferCount);
        breakdown.put(TransactionClassification.INTEREST, classificationInterestCount);
        breakdown.put(TransactionClassification.UNKNOWN, classificationUnknownCount);
        return breakdown;
    }

    /**
     * Returns total of all classification counts (should equal totalTransactions).
     */
    public int getTotalClassificationCount() {
        return classificationMerchantCount + classificationBankFeeCount +
               classificationCashWithdrawalCount + classificationCashDepositCount +
               classificationSelfTransferCount + classificationInterestCount +
               classificationUnknownCount;
    }

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
