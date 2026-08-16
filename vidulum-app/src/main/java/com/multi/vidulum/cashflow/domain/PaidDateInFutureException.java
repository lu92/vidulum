package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

import java.time.ZonedDateTime;

public class PaidDateInFutureException extends BusinessException {

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

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PAID_DATE_IN_FUTURE;
    }
}
