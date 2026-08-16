package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Exception thrown when trying to create an import job that already exists.
 */
public class ImportJobAlreadyExistsException extends BusinessException {

    private final StagingSessionId stagingSessionId;

    public ImportJobAlreadyExistsException(StagingSessionId stagingSessionId) {
        super("An active import job already exists for staging session: " + stagingSessionId.id());
        this.stagingSessionId = stagingSessionId;
    }

    public StagingSessionId getStagingSessionId() {
        return stagingSessionId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INGESTION_JOB_ALREADY_EXISTS;
    }
}
