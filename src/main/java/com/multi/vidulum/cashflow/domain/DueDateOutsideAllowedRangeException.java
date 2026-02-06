package com.multi.vidulum.cashflow.domain;

import java.time.YearMonth;
import java.time.ZonedDateTime;

/**
 * Exception thrown when a dueDate is outside the allowed range.
 * <p>
 * Allowed range: from activePeriod (current month) to activePeriod + 11 months (forecasted period).
 * <p>
 * This prevents:
 * <ul>
 *   <li>Setting dueDate to closed/historical months (before activePeriod)</li>
 *   <li>Setting dueDate to months that don't exist in forecast (more than 11 months ahead)</li>
 * </ul>
 */
public class DueDateOutsideAllowedRangeException extends RuntimeException {

    private final ZonedDateTime dueDate;
    private final YearMonth activePeriod;
    private final YearMonth maxAllowedPeriod;

    public DueDateOutsideAllowedRangeException(ZonedDateTime dueDate, YearMonth activePeriod) {
        super(String.format(
                "Due date [%s] is outside allowed range. Must be between [%s] and [%s] (activePeriod + 11 months)",
                YearMonth.from(dueDate),
                activePeriod,
                activePeriod.plusMonths(11)
        ));
        this.dueDate = dueDate;
        this.activePeriod = activePeriod;
        this.maxAllowedPeriod = activePeriod.plusMonths(11);
    }

    public ZonedDateTime getDueDate() {
        return dueDate;
    }

    public YearMonth getActivePeriod() {
        return activePeriod;
    }

    public YearMonth getMaxAllowedPeriod() {
        return maxAllowedPeriod;
    }
}
