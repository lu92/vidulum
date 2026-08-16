package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Exception thrown when rollback is not allowed for an import job.
 */
public class RollbackNotAllowedException extends BusinessException {

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

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INGESTION_ROLLBACK_NOT_ALLOWED;
    }
}
