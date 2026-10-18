package com.multi.vidulum.bank_data_ingestion.domain;

import java.util.UUID;

/**
 * Value object representing a unique identifier for a category mapping.
 */
public record MappingId(String id) {

    public static MappingId generate() {
        return new MappingId(UUID.randomUUID().toString());
    }

    public static MappingId of(String id) {
        return new MappingId(id);
    }
}
