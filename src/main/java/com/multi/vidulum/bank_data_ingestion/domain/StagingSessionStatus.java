package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Status of a staging session throughout its lifecycle.
 */
public enum StagingSessionStatus {
    /**
     * Session created, transactions are being validated.
     */
    PENDING,

    /**
     * Some transactions have unmapped categories that need attention.
     */
    HAS_UNMAPPED_CATEGORIES,

    /**
     * Transactions have validation errors (invalid dates, amounts, etc.).
     */
    HAS_VALIDATION_ERRORS,

    /**
     * AI categorization has been run and suggestions are ready for review.
     */
    AI_SUGGESTIONS_READY,

    /**
     * All transactions are validated and mapped - ready for import.
     */
    READY_FOR_IMPORT,

    /**
     * Import job is currently running for this session.
     */
    IMPORTING,

    /**
     * Import completed successfully.
     */
    COMPLETED,

    /**
     * Session expired (TTL reached) or was deleted.
     */
    EXPIRED
}
