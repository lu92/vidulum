package com.multi.vidulum.recurring_rules.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * One-time occurrence pattern - executes exactly once on a specific date.
 *
 * After execution, the rule should automatically transition to COMPLETED status.
 *
 * Examples:
 * - Car lease buyout on June 15, 2027
 * - Final loan payment
 * - Annual bonus on specific date
 */
public record OncePattern(
        LocalDate targetDate
) implements RecurrencePattern {

    public OncePattern {
        if (targetDate == null) {
            throw new IllegalArgumentException("Target date is required for one-time pattern");
        }
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.ONCE;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        // Return targetDate if it's on or after fromDate, otherwise no next occurrence
        if (!targetDate.isBefore(fromDate)) {
            return targetDate;
        }
        // No more occurrences - return a far future date to indicate end
        return LocalDate.MAX;
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        return date.equals(targetDate);
    }

    @Override
    public String toDisplayString() {
        return "One-time on " + targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
