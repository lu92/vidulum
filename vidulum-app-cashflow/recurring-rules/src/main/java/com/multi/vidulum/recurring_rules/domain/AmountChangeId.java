package com.multi.vidulum.recurring_rules.domain;

import java.util.Objects;

public record AmountChangeId(String id) {

    private static final String PREFIX = "AC";

    public AmountChangeId {
        Objects.requireNonNull(id, "Amount change ID cannot be null");
        if (!id.matches(PREFIX + "\\d+")) {
            throw new IllegalArgumentException("Invalid amount change ID format: " + id);
        }
    }

    public static AmountChangeId of(String id) {
        return new AmountChangeId(id);
    }

    public static AmountChangeId generate(long sequence) {
        return new AmountChangeId(PREFIX + String.format("%08d", sequence));
    }

    @Override
    public String toString() {
        return id;
    }
}
