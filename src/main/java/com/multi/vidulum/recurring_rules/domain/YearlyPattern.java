package com.multi.vidulum.recurring_rules.domain;

import java.time.LocalDate;
import java.time.Month;

public record YearlyPattern(int month, int dayOfMonth) implements RecurrencePattern {

    public YearlyPattern {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Month must be between 1 and 12");
        }
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            throw new IllegalArgumentException("Day of month must be between 1 and 31");
        }
    }

    @Override
    public RecurrenceType type() {
        return RecurrenceType.YEARLY;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        LocalDate candidate = LocalDate.of(fromDate.getYear(), month, 1);
        int targetDay = Math.min(dayOfMonth, candidate.lengthOfMonth());
        candidate = candidate.withDayOfMonth(targetDay);

        if (candidate.isBefore(fromDate)) {
            candidate = LocalDate.of(fromDate.getYear() + 1, month, 1);
            targetDay = Math.min(dayOfMonth, candidate.lengthOfMonth());
            candidate = candidate.withDayOfMonth(targetDay);
        }

        return candidate;
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        return date.getMonthValue() == month &&
                date.getDayOfMonth() == Math.min(dayOfMonth, date.lengthOfMonth());
    }

    @Override
    public String toDisplayString() {
        String monthName = Month.of(month).toString();
        monthName = monthName.charAt(0) + monthName.substring(1).toLowerCase();
        return "Yearly on " + monthName + " " + dayOfMonth;
    }
}
