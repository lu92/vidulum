package com.multi.vidulum.bank_data_ingestion.app.commands.accept_ai_suggestions;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.util.List;

/**
 * Result of accepting AI categorization suggestions.
 */
public record AcceptAiSuggestionsResult(
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
    public static AcceptAiSuggestionsResult success(
            CashFlowId cashFlowId,
            StagingSessionId sessionId,
            int categoriesCreated,
            int mappingsApplied,
            int patternsCached,
            StagingValidationSummary validationSummary) {

        return new AcceptAiSuggestionsResult(
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
    public static AcceptAiSuggestionsResult partial(
            CashFlowId cashFlowId,
            StagingSessionId sessionId,
            int categoriesCreated,
            int mappingsApplied,
            int patternsCached,
            List<String> warnings,
            StagingValidationSummary validationSummary) {

        return new AcceptAiSuggestionsResult(
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
