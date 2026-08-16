package com.multi.vidulum.recurring_rules.domain;

import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRecurrencePatternException;

import java.time.LocalDate;
import java.time.Month;

/**
 * Quarterly recurrence pattern - executes on a specific day of a specific month within each quarter.
 *
 * Examples:
 * - VAT payment on 25th of 1st month of quarter (Jan, Apr, Jul, Oct)
 * - Quarterly report on last day of 3rd month of quarter (Mar, Jun, Sep, Dec)
 */
public record QuarterlyPattern(
        int monthInQuarter,
        int dayOfMonth
) implements RecurrencePattern {

    /**
     * Special value for dayOfMonth indicating "last day of month".
     */
    public static final int LAST_DAY_OF_MONTH = -1;

    public QuarterlyPattern {
        if (monthInQuarter < 1 || monthInQuarter > 3) {
            throw new InvalidRecurrencePatternException("QUARTERLY", "Month in quarter must be 1, 2, or 3");
        }
        if (dayOfMonth < LAST_DAY_OF_MONTH || dayOfMonth == 0 || dayOfMonth > 31) {
            throw new InvalidRecurrencePatternException("QUARTERLY", "Day of month must be between 1 and 31, or -1 for last day of month");
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
        return RecurrenceType.QUARTERLY;
    }

    @Override
    public LocalDate nextOccurrenceFrom(LocalDate fromDate) {
        // Find the quarter start month for fromDate
        int quarterStartMonth = ((fromDate.getMonthValue() - 1) / 3) * 3 + 1;
        int targetMonthInYear = quarterStartMonth + (monthInQuarter - 1);

        // Calculate target date in current quarter
        LocalDate candidate = calculateDateInMonth(fromDate.getYear(), targetMonthInYear);

        // If candidate is before fromDate, move to next quarter
        if (candidate.isBefore(fromDate)) {
            targetMonthInYear += 3;
            if (targetMonthInYear > 12) {
                targetMonthInYear -= 12;
                candidate = calculateDateInMonth(fromDate.getYear() + 1, targetMonthInYear);
            } else {
                candidate = calculateDateInMonth(fromDate.getYear(), targetMonthInYear);
            }
        }

        return candidate;
    }

    private LocalDate calculateDateInMonth(int year, int month) {
        LocalDate firstOfMonth = LocalDate.of(year, month, 1);
        int targetDay;

        if (isLastDayOfMonth()) {
            targetDay = firstOfMonth.lengthOfMonth();
        } else {
            targetDay = Math.min(dayOfMonth, firstOfMonth.lengthOfMonth());
        }

        return LocalDate.of(year, month, targetDay);
    }

    @Override
    public boolean isValidForDate(LocalDate date) {
        // Check if month is the correct month in its quarter
        int monthInQ = ((date.getMonthValue() - 1) % 3) + 1;
        if (monthInQ != monthInQuarter) {
            return false;
        }

        // Check day of month
        if (isLastDayOfMonth()) {
            return date.getDayOfMonth() == date.lengthOfMonth();
        }
        int expectedDay = Math.min(dayOfMonth, date.lengthOfMonth());
        return date.getDayOfMonth() == expectedDay;
    }

    @Override
    public String toDisplayString() {
        String monthDesc = switch (monthInQuarter) {
            case 1 -> "1st month";
            case 2 -> "2nd month";
            case 3 -> "3rd month";
            default -> monthInQuarter + "th month";
        };

        if (isLastDayOfMonth()) {
            return "Quarterly on the last day of " + monthDesc;
        }

        String daySuffix = getDaySuffix(dayOfMonth);
        return "Quarterly on the " + dayOfMonth + daySuffix + " of " + monthDesc;
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
