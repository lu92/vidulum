package com.multi.vidulum.cashflow.domain;

import java.time.ZonedDateTime;

public class PaidDateInFutureException extends RuntimeException {

    private final ZonedDateTime paidDate;
    private final ZonedDateTime now;

    public PaidDateInFutureException(ZonedDateTime paidDate, ZonedDateTime now) {
        super(String.format("Paid date [%s] cannot be in the future. Current time: [%s]", paidDate, now));
        this.paidDate = paidDate;
        this.now = now;
    }

    public ZonedDateTime getPaidDate() {
        return paidDate;
    }

    public ZonedDateTime getNow() {
        return now;
    }
}
