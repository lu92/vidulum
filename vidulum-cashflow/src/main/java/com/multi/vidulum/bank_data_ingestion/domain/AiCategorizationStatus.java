package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Status of AI categorization for a staging session.
 */
public enum AiCategorizationStatus {
    /**
     * AI categorization has not been started yet.
     */
    NOT_STARTED,

    /**
     * AI categorization is currently in progress.
     */
    IN_PROGRESS,

    /**
     * AI categorization completed successfully with suggestions.
     */
    COMPLETED,

    /**
     * AI categorization was skipped (user chose manual mapping or force uncategorized).
     */
    SKIPPED,

    /**
     * AI categorization failed.
     */
    FAILED
}
