package com.multi.vidulum.bank_data_ingestion.domain;

import java.util.UUID;

/**
 * Value object representing a unique identifier for a staged transaction.
 */
public record StagedTransactionId(String id) {

    public static StagedTransactionId generate() {
        return new StagedTransactionId(UUID.randomUUID().toString());
    }

    public static StagedTransactionId of(String id) {
        return new StagedTransactionId(id);
    }
}
