package com.multi.vidulum.cashflow.domain;

import java.util.regex.Pattern;

/**
 * Value object representing a CashFlow ID.
 * Format: CF + 8 digits (e.g., CF10000001)
 *
 * <p>Use {@link com.multi.vidulum.common.BusinessIdGenerator#generateCashFlowId()} to create new IDs.
 * Use {@link CashFlowId#of(String)} to parse existing IDs (validates format).
 *
 * <p><b>Constraints:</b>
 * <ul>
 *   <li>Must match pattern: CF followed by exactly 8 digits</li>
 *   <li>Valid range: CF00000000 - CF99999999 (100 million unique IDs)</li>
 *   <li>Starting value in production: CF10000001</li>
 * </ul>
 */
public record CashFlowId(String id) {

    private static final Pattern PATTERN = Pattern.compile("CF\\d{8}");

    /**
     * Compact constructor - validates format.
     * Allows null for deserialization edge cases, throws for invalid non-null values.
     *
     * @throws InvalidCashFlowIdFormatException if id is non-null and doesn't match CFXXXXXXXX pattern
     */
    public CashFlowId {
        if (id != null && !PATTERN.matcher(id).matches()) {
            throw new InvalidCashFlowIdFormatException(id);
        }
    }

    /**
     * Creates a CashFlowId from a string, validating the format.
     *
     * @param id the CashFlow ID string (must match pattern CFXXXXXXXX)
     * @return validated CashFlowId
     * @throws InvalidCashFlowIdFormatException if the format is invalid or null
     */
    public static CashFlowId of(String id) {
        if (id == null || !PATTERN.matcher(id).matches()) {
            throw new InvalidCashFlowIdFormatException(id);
        }
        return new CashFlowId(id);
    }
}
