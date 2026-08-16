package com.multi.vidulum.bank_data_adapter.domain;

/**
 * Status of the transformation import.
 */
public enum ImportStatus {
    PENDING,        // Transformation OK, waiting for import
    IMPORTED,       // Imported to CashFlow
    SKIPPED,        // User skipped import
    FAILED          // Import failed
}
