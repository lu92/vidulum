package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

import java.time.YearMonth;

public class StartPeriodInFutureException extends BusinessException {

    private final YearMonth startPeriod;
    private final YearMonth activePeriod;

    public StartPeriodInFutureException(YearMonth startPeriod, YearMonth activePeriod) {
        super(String.format("Start period [%s] cannot be in the future. Current active period: [%s]",
                startPeriod, activePeriod));
        this.startPeriod = startPeriod;
        this.activePeriod = activePeriod;
    }

    public YearMonth getStartPeriod() {
        return startPeriod;
    }

    public YearMonth getActivePeriod() {
        return activePeriod;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.START_PERIOD_IN_FUTURE;
    }
}
