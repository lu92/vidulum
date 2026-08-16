package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Exception thrown when an operation requires a completed import job but the job is not completed.
 */
public class ImportJobNotCompletedException extends BusinessException {

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

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INGESTION_JOB_NOT_COMPLETED;
    }
}
