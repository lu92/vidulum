package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Exception thrown when an import job is not found.
 */
public class ImportJobNotFoundException extends BusinessException {

    private final ImportJobId jobId;

    public ImportJobNotFoundException(ImportJobId jobId) {
        super("Import job not found: " + jobId.id());
        this.jobId = jobId;
    }

    public ImportJobId getJobId() {
        return jobId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INGESTION_IMPORT_JOB_NOT_FOUND;
    }
}
