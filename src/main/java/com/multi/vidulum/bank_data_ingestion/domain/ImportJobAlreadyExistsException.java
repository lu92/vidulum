package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Exception thrown when trying to create an import job that already exists.
 */
public class ImportJobAlreadyExistsException extends RuntimeException {

    private final StagingSessionId stagingSessionId;

    public ImportJobAlreadyExistsException(StagingSessionId stagingSessionId) {
        super("An active import job already exists for staging session: " + stagingSessionId.id());
        this.stagingSessionId = stagingSessionId;
    }

    public StagingSessionId getStagingSessionId() {
        return stagingSessionId;
    }
}
