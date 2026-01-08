package com.multi.vidulum.bank_data_ingestion.domain;

/**
 * Exception thrown when a staging session is not ready for import.
 */
public class StagingSessionNotReadyException extends RuntimeException {

    private final StagingSessionId stagingSessionId;
    private final String reason;

    public StagingSessionNotReadyException(StagingSessionId stagingSessionId, String reason) {
        super("Staging session " + stagingSessionId.id() + " is not ready for import: " + reason);
        this.stagingSessionId = stagingSessionId;
        this.reason = reason;
    }

    public StagingSessionId getStagingSessionId() {
        return stagingSessionId;
    }

    public String getReason() {
        return reason;
    }
}
