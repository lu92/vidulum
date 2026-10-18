package com.multi.vidulum.bank_data_ingestion.domain;

import java.util.UUID;

/**
 * Value object representing a unique identifier for a staging session.
 * A staging session groups transactions from the same staging operation.
 */
public record StagingSessionId(String id) {

    public static StagingSessionId generate() {
        return new StagingSessionId(UUID.randomUUID().toString());
    }

    public static StagingSessionId of(String id) {
        return new StagingSessionId(id);
    }
}
