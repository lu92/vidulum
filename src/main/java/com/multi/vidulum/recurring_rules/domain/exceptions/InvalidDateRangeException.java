package com.multi.vidulum.recurring_rules.domain.exceptions;

import java.time.LocalDate;

/**
 * Thrown when the start/end date range is invalid.
 */
public class InvalidDateRangeException extends RecurringRuleException {

    private final LocalDate startDate;
    private final LocalDate endDate;

    public InvalidDateRangeException(LocalDate startDate, LocalDate endDate) {
        super(String.format("Invalid date range: start [%s] must be before or equal to end [%s]", startDate, endDate));
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }
}
