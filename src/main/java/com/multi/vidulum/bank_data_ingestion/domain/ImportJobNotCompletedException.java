package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Exception thrown when an operation requires a completed import job but the job is not completed.
 */
public class ImportJobNotCompletedException extends RuntimeException {

    private final ImportJobId jobId;
    private final ImportJobStatus currentStatus;

    public ImportJobNotCompletedException(ImportJobId jobId, ImportJobStatus currentStatus) {
        super("Import job " + jobId.id() + " is not completed. Current status: " + currentStatus);
        this.jobId = jobId;
        this.currentStatus = currentStatus;
    }

    public ImportJobId getJobId() {
        return jobId;
    }

    public ImportJobStatus getCurrentStatus() {
        return currentStatus;
    }
}
