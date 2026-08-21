package com.multi.vidulum.common;

import java.util.Objects;

/**
 * Normalized bank account identifier (typically IBAN).
 * Used for account matching (e.g., self-transfer detection) without
 * requiring full IBAN validation or iban4j dependency.
 *
 * <p>Normalizes input by removing whitespace and converting to uppercase.
 */
public record BankAccountId(String value) {

    public BankAccountId {
        Objects.requireNonNull(value, "Bank account ID cannot be null");
        value = value.replaceAll("\\s+", "").toUpperCase();
    }
}
