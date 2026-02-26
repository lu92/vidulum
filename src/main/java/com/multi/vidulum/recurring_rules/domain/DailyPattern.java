package com.multi.vidulum.recurring_rules.domain;

import java.time.LocalDate;

public record DailyPattern(int intervalDays) implements RecurrencePattern {

    public DailyPattern {
        if (intervalDays < 1 || intervalDays > 365) {
            throw new IllegalArgumentException("Interval must be between 1 and 365 days");
        }
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.DAILY;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        return fromDate;
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        return true;
    }

    @Override
    public String toDisplayString() {
        return intervalDays == 1 ? "Every day" : "Every " + intervalDays + " days";
    }
}
