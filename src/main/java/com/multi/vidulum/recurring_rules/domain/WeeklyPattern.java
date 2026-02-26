package com.multi.vidulum.recurring_rules.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;

public record WeeklyPattern(DayOfWeek dayOfWeek, int intervalWeeks) implements RecurrencePattern {

    public WeeklyPattern {
        Objects.requireNonNull(dayOfWeek, "Day of week cannot be null");
        if (intervalWeeks < 1 || intervalWeeks > 52) {
            throw new IllegalArgumentException("Interval must be between 1 and 52 weeks");
        }
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.WEEKLY;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        LocalDate candidate = fromDate;
        while (candidate.getDayOfWeek() != dayOfWeek) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        return date.getDayOfWeek() == dayOfWeek;
    }

    @Override
    public String toDisplayString() {
        String dayName = dayOfWeek.toString().charAt(0) +
                dayOfWeek.toString().substring(1).toLowerCase();
        return intervalWeeks == 1
                ? "Every " + dayName
                : "Every " + intervalWeeks + " weeks on " + dayName;
    }
}
