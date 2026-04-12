package com.multi.vidulum.bank_data_ingestion.domain;

import java.util.UUID;

/**
 * Value object representing a unique identifier for a PatternMapping.
 */
public record PatternMappingId(String id) {

    public PatternMappingId {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("PatternMappingId cannot be null or blank");
        }
    }

    public static PatternMappingId generate() {
        return new PatternMappingId(UUID.randomUUID().toString());
    }

    public static PatternMappingId of(String id) {
        return new PatternMappingId(id);
    }
}
