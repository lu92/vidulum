package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRecurrencePatternException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Every N days recurrence pattern with optional preferred day of week adjustment.
 *
 * When preferredDayOfWeek is set, the occurrence date will be adjusted to the nearest
 * preferred day within a ±3 day window from the calculated date.
 *
 * Examples:
 * - Bi-weekly payroll every 14 days on Friday
 * - 28-day subscription cycle (no day preference)
 * - Every 10 days medication reminder
 */
public record EveryNDaysPattern(
        int intervalDays,
        DayOfWeek preferredDayOfWeek
) implements RecurrencePattern {

    /**
     * Maximum adjustment window in days when snapping to preferred day of week.
     */
    private static final int MAX_ADJUSTMENT_DAYS = 3;

    public EveryNDaysPattern {
        if (intervalDays < 1 || intervalDays > 365) {
            throw new InvalidRecurrencePatternException("EVERY_N_DAYS", "Interval must be between 1 and 365 days");
        }
    }

    /**
     * Creates a pattern without preferred day of week.
     */
    public static EveryNDaysPattern withoutPreference(int intervalDays) {
        return new EveryNDaysPattern(intervalDays, null);
    }

    /**
     * Creates a pattern with preferred day of week.
     */
    public static EveryNDaysPattern withPreferredDay(int intervalDays, DayOfWeek preferredDayOfWeek) {
        return new EveryNDaysPattern(intervalDays, preferredDayOfWeek);
    }

    public boolean hasPreferredDayOfWeek() {
        return preferredDayOfWeek != null;
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.EVERY_N_DAYS;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        LocalDate candidate = fromDate;

        if (hasPreferredDayOfWeek()) {
            // Adjust to preferred day of week within the adjustment window
            candidate = adjustToPreferredDay(candidate);
        }

        return candidate;
    }

    /**
     * Adjusts the given date to the nearest preferred day of week within ±MAX_ADJUSTMENT_DAYS window.
     */
    private LocalDate adjustToPreferredDay(LocalDate date) {
        if (preferredDayOfWeek == null) {
            return date;
        }

        // Find the previous and next occurrence of the preferred day
        LocalDate previousPreferred = date.with(TemporalAdjusters.previousOrSame(preferredDayOfWeek));
        LocalDate nextPreferred = date.with(TemporalAdjusters.nextOrSame(preferredDayOfWeek));

        // Calculate distances
        long daysToPrevious = Math.abs(date.toEpochDay() - previousPreferred.toEpochDay());
        long daysToNext = Math.abs(nextPreferred.toEpochDay() - date.toEpochDay());

        // Choose the closer one, preferring next if equal
        LocalDate adjusted;
        if (daysToPrevious <= daysToNext && daysToPrevious <= MAX_ADJUSTMENT_DAYS) {
            adjusted = previousPreferred;
        } else if (daysToNext <= MAX_ADJUSTMENT_DAYS) {
            adjusted = nextPreferred;
        } else {
            // No preferred day within window, use original date
            adjusted = date;
        }

        return adjusted;
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        // For EVERY_N_DAYS, any date is potentially valid
        // The actual validation happens during occurrence generation
        // based on the start date and interval
        return true;
    }

    @Override
    public String toDisplayString() {
        String baseDesc = intervalDays == 1 ? "Every day" : "Every " + intervalDays + " days";

        if (hasPreferredDayOfWeek()) {
            String dayName = preferredDayOfWeek.toString().charAt(0) +
                    preferredDayOfWeek.toString().substring(1).toLowerCase();
            return baseDesc + " (preferably on " + dayName + ")";
        }

        return baseDesc;
    }
}
