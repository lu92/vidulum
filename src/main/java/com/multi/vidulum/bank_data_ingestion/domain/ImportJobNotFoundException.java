package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Exception thrown when an import job is not found.
 */
public class ImportJobNotFoundException extends RuntimeException {

    private final ImportJobId jobId;

    public ImportJobNotFoundException(ImportJobId jobId) {
        super("Import job not found: " + jobId.id());
        this.jobId = jobId;
    }

    public ImportJobId getJobId() {
        return jobId;
    }
}
