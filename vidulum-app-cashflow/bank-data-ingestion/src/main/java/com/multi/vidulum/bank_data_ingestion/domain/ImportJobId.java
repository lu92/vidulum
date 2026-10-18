package com.multi.vidulum.bank_data_ingestion.domain;

import java.util.UUID;

/**
 * Unique identifier for an import job.
 */
public record ImportJobId(String id) {

    public static ImportJobId generate() {
        return new ImportJobId(UUID.randomUUID().toString());
    }

    public static ImportJobId of(String id) {
        return new ImportJobId(id);
    }
}
