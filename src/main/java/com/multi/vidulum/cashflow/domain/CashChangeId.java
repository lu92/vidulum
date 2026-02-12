package com.multi.vidulum.cashflow.domain;

import java.util.regex.Pattern;

/**
 * Value object representing a CashChange ID.
 * Format: CC + 10 digits (e.g., CC1000000001)
 *
 * <p>Use {@link com.multi.vidulum.common.BusinessIdGenerator#generateCashChangeId()} to create new IDs.
 * Use {@link CashChangeId#of(String)} to parse existing IDs (validates format).
 *
 * <p><b>Constraints:</b>
 * <ul>
 *   <li>Must match pattern: CC followed by exactly 10 digits</li>
 *   <li>Valid range: CC0000000000 - CC9999999999 (10 billion unique IDs)</li>
 *   <li>Starting value in production: CC1000000001</li>
 * </ul>
 */
public record CashChangeId(String id) {

    private static final Pattern PATTERN = Pattern.compile("CC\\d{10}");

    /**
     * Compact constructor - validates format.
     * Allows null for deserialization edge cases, throws for invalid non-null values.
     *
     * @throws InvalidCashChangeIdFormatException if id is non-null and doesn't match CCXXXXXXXXXX pattern
     */
    public CashChangeId {
        if (id != null && !PATTERN.matcher(id).matches()) {
            throw new InvalidCashChangeIdFormatException(id);
        }
    }

    /**
     * Creates a CashChangeId from a string, validating the format.
     *
     * @param id the CashChange ID string (must match pattern CCXXXXXXXXXX)
     * @return validated CashChangeId
     * @throws InvalidCashChangeIdFormatException if the format is invalid or null
     */
    public static CashChangeId of(String id) {
        if (id == null || !PATTERN.matcher(id).matches()) {
            throw new InvalidCashChangeIdFormatException(id);
        }
        return new CashChangeId(id);
    }
}
