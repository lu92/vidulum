package com.multi.vidulum.cashflow.domain;

import java.time.YearMonth;

public class StartPeriodInFutureException extends RuntimeException {

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
}
