package com.multi.vidulum.bank_data_ingestion.classification.commands.accept_classification;

import com.multi.vidulum.bank_data_ingestion.staging.domain.StagingSessionId;
import com.multi.vidulum.common.CashFlowId;

import java.util.List;

/**
 * Result of accepting AI categorization suggestions.
 */
public record AcceptClassificationResult(
        CashFlowId cashFlowId,
        StagingSessionId sessionId,
        String status,
        int categoriesCreated,
        int mappingsApplied,
        int patternsCached,
        List<String> warnings,
        StagingValidationSummary validationSummary
) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_ERROR = "ERROR";

    /**
     * Summary of staging session validation after applying mappings.
     */
    public record StagingValidationSummary(
            int totalTransactions,
            int validTransactions,
            int invalidTransactions,
            int duplicateTransactions,
            boolean readyForImport
    ) {}

    /**
     * Creates a successful result.
     */
    public static AcceptClassificationResult success(
            CashFlowId cashFlowId,
            StagingSessionId sessionId,
            int categoriesCreated,
            int mappingsApplied,
            int patternsCached,
            StagingValidationSummary validationSummary) {

        return new AcceptClassificationResult(
                cashFlowId,
                sessionId,
                STATUS_SUCCESS,
                categoriesCreated,
                mappingsApplied,
                patternsCached,
                List.of(),
                validationSummary
        );
    }

    /**
     * Creates a partial success result (some operations failed).
     */
    public static AcceptClassificationResult partial(
            CashFlowId cashFlowId,
            StagingSessionId sessionId,
            int categoriesCreated,
            int mappingsApplied,
            int patternsCached,
            List<String> warnings,
            StagingValidationSummary validationSummary) {

        return new AcceptClassificationResult(
                cashFlowId,
                sessionId,
                STATUS_PARTIAL,
                categoriesCreated,
                mappingsApplied,
                patternsCached,
                warnings,
                validationSummary
        );
    }
}
