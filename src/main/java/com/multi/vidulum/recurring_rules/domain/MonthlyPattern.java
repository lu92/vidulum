package com.multi.vidulum.recurring_rules.domain;

import java.time.LocalDate;

public record MonthlyPattern(
        int dayOfMonth,
        int intervalMonths,
        boolean adjustForMonthEnd
) implements RecurrencePattern {

    public MonthlyPattern {
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            throw new IllegalArgumentException("Day of month must be between 1 and 31");
        }
        if (intervalMonths < 1 || intervalMonths > 12) {
            throw new IllegalArgumentException("Interval must be between 1 and 12 months");
        }
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.MONTHLY;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        int targetDay = Math.min(dayOfMonth, fromDate.lengthOfMonth());

        if (adjustForMonthEnd && dayOfMonth > fromDate.lengthOfMonth()) {
            targetDay = fromDate.lengthOfMonth();
        }

        LocalDate candidate = fromDate.withDayOfMonth(targetDay);
        if (candidate.isBefore(fromDate)) {
            candidate = candidate.plusMonths(intervalMonths);
            targetDay = Math.min(dayOfMonth, candidate.lengthOfMonth());
            candidate = candidate.withDayOfMonth(targetDay);
        }

        return candidate;
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        int expectedDay = Math.min(dayOfMonth, date.lengthOfMonth());
        return date.getDayOfMonth() == expectedDay;
    }

    @Override
    public String toDisplayString() {
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
