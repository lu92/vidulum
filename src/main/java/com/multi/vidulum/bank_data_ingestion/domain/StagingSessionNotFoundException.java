package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Exception thrown when a staging session is not found.
 */
public class StagingSessionNotFoundException extends RuntimeException {

    public StagingSessionNotFoundException(StagingSessionId stagingSessionId) {
        super("Staging session not found: " + stagingSessionId.id());
    }
}
