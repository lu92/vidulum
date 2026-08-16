package com.multi.vidulum.common;

import java.util.regex.Pattern;

/**
 * Value object representing a CashChange ID.
 * Format: CC + 10 digits (e.g., CC1000000001)
 */
public record CashChangeId(String id) {

    private static final Pattern PATTERN = Pattern.compile("CC\\d{10}");

    public CashChangeId {
        if (id != null && !PATTERN.matcher(id).matches()) {
            throw new InvalidCashChangeIdFormatException(id);
        }
    }

    public static CashChangeId of(String id) {
        if (id == null || !PATTERN.matcher(id).matches()) {
            throw new InvalidCashChangeIdFormatException(id);
        }
        return new CashChangeId(id);
    }
}
