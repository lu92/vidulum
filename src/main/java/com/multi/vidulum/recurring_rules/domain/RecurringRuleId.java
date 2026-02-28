package com.multi.vidulum.recurring_rules.domain;

import java.util.Objects;

public record RecurringRuleId(String id) {

    private static final String PREFIX = "RR";

    public RecurringRuleId {
        Objects.requireNonNull(id, "Rule ID cannot be null");
        if (!id.matches(PREFIX + "\\d+")) {
            throw new IllegalArgumentException("Invalid rule ID format: " + id);
        }
    }

    public static RecurringRuleId of(String id) {
        return new RecurringRuleId(id);
    }

    public static RecurringRuleId generate(long sequence) {
        return new RecurringRuleId(PREFIX + String.format("%08d", sequence));
    }

    @Override
    public String toString() {
        return id;
    }
}
