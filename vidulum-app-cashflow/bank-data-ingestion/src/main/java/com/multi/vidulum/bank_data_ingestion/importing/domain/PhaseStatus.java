package com.multi.vidulum.bank_data_ingestion.importing.domain;

/**
 * Status of a single import phase.
 */
public enum PhaseStatus {
    /**
     * Phase not yet started.
     */
    PENDING,

    /**
     * Phase is currently in progress.
     */
    IN_PROGRESS,

    /**
     * Phase completed successfully.
     */
    COMPLETED,

    /**
     * Phase failed.
     */
    FAILED
}
