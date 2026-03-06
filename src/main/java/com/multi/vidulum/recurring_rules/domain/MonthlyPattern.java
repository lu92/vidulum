package com.multi.vidulum.recurring_rules.domain;

import java.time.LocalDate;

public record MonthlyPattern(
        int dayOfMonth,
        int intervalMonths,
        boolean adjustForMonthEnd
) implements RecurrencePattern {

    /**
     * Special value for dayOfMonth indicating "last day of month".
     * When used, the pattern will generate occurrences on the last day of each month
     * (28, 29, 30, or 31 depending on the month and year).
     */
    public static final int LAST_DAY_OF_MONTH = -1;

    public MonthlyPattern {
        if (dayOfMonth < LAST_DAY_OF_MONTH || dayOfMonth == 0 || dayOfMonth > 31) {
            throw new IllegalArgumentException("Day of month must be between 1 and 31, or -1 for last day of month");
        }
        if (intervalMonths < 1 || intervalMonths > 12) {
            throw new IllegalArgumentException("Interval must be between 1 and 12 months");
        }
    }

    /**
     * Returns true if this pattern uses the last day of month (-1).
     */
    public boolean isLastDayOfMonth() {
        return dayOfMonth == LAST_DAY_OF_MONTH;
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.MONTHLY;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        int targetDay;

        if (isLastDayOfMonth()) {
            // Use last day of the current month
            targetDay = fromDate.lengthOfMonth();
        } else {
            targetDay = Math.min(dayOfMonth, fromDate.lengthOfMonth());
            if (adjustForMonthEnd && dayOfMonth > fromDate.lengthOfMonth()) {
                targetDay = fromDate.lengthOfMonth();
            }
        }

        LocalDate candidate = fromDate.withDayOfMonth(targetDay);
        if (candidate.isBefore(fromDate)) {
            candidate = candidate.plusMonths(intervalMonths);
            if (isLastDayOfMonth()) {
                targetDay = candidate.lengthOfMonth();
            } else {
                targetDay = Math.min(dayOfMonth, candidate.lengthOfMonth());
            }
            candidate = candidate.withDayOfMonth(targetDay);
        }

        return candidate;
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        if (isLastDayOfMonth()) {
            return date.getDayOfMonth() == date.lengthOfMonth();
        }
        int expectedDay = Math.min(dayOfMonth, date.lengthOfMonth());
        return date.getDayOfMonth() == expectedDay;
    }

    @Override
    public String toDisplayString() {
        if (isLastDayOfMonth()) {
            return intervalMonths == 1
                    ? "Monthly on the last day"
                    : "Every " + intervalMonths + " months on the last day";
        }
        String daySuffix = getDaySuffix(dayOfMonth);
        return intervalMonths == 1
                ? "Monthly on the " + dayOfMonth + daySuffix
                : "Every " + intervalMonths + " months on the " + dayOfMonth + daySuffix;
    }

    private static String getDaySuffix(int day) {
        if (day >= 11 && day <= 13) return "th";
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }
}
