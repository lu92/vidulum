package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Status of an import job.
 */
public enum ImportJobStatus {
    /**
     * Job created but not yet started.
     */
    PENDING,

    /**
     * Job is currently processing (creating categories or importing transactions).
     */
    PROCESSING,

    /**
     * Job completed successfully. Can still be rolled back.
     */
    COMPLETED,

    /**
     * Job failed during processing.
     */
    FAILED,

    /**
     * Job was rolled back. All imported data was deleted.
     */
    ROLLED_BACK,

    /**
     * Job was finalized. Staging data deleted, history kept.
     */
    FINALIZED
}
