package com.multi.vidulum.cashflow.domain;

import java.time.YearMonth;
import java.time.ZonedDateTime;

public class PaidDateNotInActivePeriodException extends RuntimeException {

    private final ZonedDateTime paidDate;
    private final YearMonth activePeriod;

    public PaidDateNotInActivePeriodException(ZonedDateTime paidDate, YearMonth activePeriod) {
        super(String.format("Paid date [%s] must be in active period [%s]. Paid transactions can only be added to the current active month.", paidDate, activePeriod));
        this.paidDate = paidDate;
        this.activePeriod = activePeriod;
    }

    public ZonedDateTime getPaidDate() {
        return paidDate;
    }

    public YearMonth getActivePeriod() {
        return activePeriod;
    }
}
