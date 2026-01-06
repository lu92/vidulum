package com.multi.vidulum.cashflow.domain;

import java.time.ZonedDateTime;

/**
 * Exception thrown when attempting to import historical data with a paidDate in the future.
 * Historical imports can only include transactions that have already occurred (paidDate <= now).
 */
public class ImportDateInFutureException extends RuntimeException {

    private final ZonedDateTime paidDate;
    private final ZonedDateTime currentTime;

    public ImportDateInFutureException(ZonedDateTime paidDate, ZonedDateTime currentTime) {
        super(String.format("Cannot import transaction with paidDate [%s] because it is in the future. " +
                        "Current time is [%s]. Historical imports can only include past transactions.",
                paidDate, currentTime));
        this.paidDate = paidDate;
        this.currentTime = currentTime;
    }

    public ZonedDateTime getPaidDate() {
        return paidDate;
    }

    public ZonedDateTime getCurrentTime() {
        return currentTime;
    }
}
