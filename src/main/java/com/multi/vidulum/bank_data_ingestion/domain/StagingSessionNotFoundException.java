package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Exception thrown when a staging session is not found.
 */
public class StagingSessionNotFoundException extends BusinessException {

    public StagingSessionNotFoundException(StagingSessionId stagingSessionId) {
        super("Staging session not found: " + stagingSessionId.id());
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INGESTION_STAGING_NOT_FOUND;
    }
}
