package com.multi.vidulum.bank_data_adapter.app.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of enrichment for a single batch of transactions.
 * This is the expected JSON structure from AI response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnrichmentBatchResult {

    /**
     * Whether the AI processing succeeded.
     */
    @JsonProperty("success")
    private boolean success;

    /**
     * List of enriched transactions.
     */
    @JsonProperty("enrichedTransactions")
    private List<EnrichedTransactionJson> enrichedTransactions;

    /**
     * Optional notes from AI about ambiguous cases or processing issues.
     */
    @JsonProperty("processingNotes")
    private String processingNotes;

    /**
     * Error message if success=false.
     */
    @JsonProperty("errorMessage")
    private String errorMessage;

    /**
     * JSON structure for a single enriched transaction from AI.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EnrichedTransactionJson {

        @JsonProperty("rowIndex")
        private int rowIndex;

        @JsonProperty("merchant")
        private String merchant;

        @JsonProperty("merchantConfidence")
        private double merchantConfidence;

        @JsonProperty("bankCategory")
        private String bankCategory;

        @JsonProperty("bankCategorySource")
        private String bankCategorySource;
    }
}
