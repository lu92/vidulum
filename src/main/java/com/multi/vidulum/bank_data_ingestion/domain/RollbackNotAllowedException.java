package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Exception thrown when rollback is not allowed for an import job.
 */
public class RollbackNotAllowedException extends RuntimeException {

    private final ImportJobId jobId;
    private final String reason;

    public RollbackNotAllowedException(ImportJobId jobId, String reason) {
        super("Rollback not allowed for import job " + jobId.id() + ": " + reason);
        this.jobId = jobId;
        this.reason = reason;
    }

    public ImportJobId getJobId() {
        return jobId;
    }

    public String getReason() {
        return reason;
    }
}
