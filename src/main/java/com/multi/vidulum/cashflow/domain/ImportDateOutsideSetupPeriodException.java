package com.multi.vidulum.cashflow.domain;

import java.time.YearMonth;
import java.time.ZonedDateTime;

/**
 * Exception thrown when attempting to import historical data to a month that is not in SETUP_PENDING status.
 * Historical imports are only allowed to months between startPeriod and activePeriod-1.
 */
public class ImportDateOutsideSetupPeriodException extends RuntimeException {

    private final ZonedDateTime importDate;
    private final YearMonth targetPeriod;
    private final YearMonth activePeriod;

    public ImportDateOutsideSetupPeriodException(ZonedDateTime importDate, YearMonth targetPeriod, YearMonth activePeriod) {
        super(String.format("Cannot import historical transaction with date [%s] (period [%s]). " +
                        "Historical imports are only allowed to months before the active period [%s].",
                importDate, targetPeriod, activePeriod));
        this.importDate = importDate;
        this.targetPeriod = targetPeriod;
        this.activePeriod = activePeriod;
    }

    public ZonedDateTime getImportDate() {
        return importDate;
    }

    public YearMonth getTargetPeriod() {
        return targetPeriod;
    }

    public YearMonth getActivePeriod() {
        return activePeriod;
    }
}
