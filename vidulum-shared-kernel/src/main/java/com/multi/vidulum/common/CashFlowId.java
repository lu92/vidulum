package com.multi.vidulum.common;

import java.util.regex.Pattern;

/**
 * Value object representing a CashFlow ID.
 * Format: CF + 8 digits (e.g., CF10000001)
 */
public record CashFlowId(String id) {

    private static final Pattern PATTERN = Pattern.compile("CF\\d{8}");

    public CashFlowId {
        if (id != null && !PATTERN.matcher(id).matches()) {
            throw new InvalidCashFlowIdFormatException(id);
        }
    }

    public static CashFlowId of(String id) {
        if (id == null || !PATTERN.matcher(id).matches()) {
            throw new InvalidCashFlowIdFormatException(id);
        }
        return new CashFlowId(id);
    }
}
