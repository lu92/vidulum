package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Validation status for a staged transaction.
 */
public enum ValidationStatus {
    /**
     * Transaction is valid and ready for import.
     */
    VALID,

    /**
     * Transaction has validation errors and cannot be imported.
     */
    INVALID,

    /**
     * Transaction is a duplicate of an existing transaction.
     */
    DUPLICATE,

    /**
     * Transaction is pending category mapping configuration.
     * Will be revalidated when mappings are configured.
     */
    PENDING_MAPPING
}
