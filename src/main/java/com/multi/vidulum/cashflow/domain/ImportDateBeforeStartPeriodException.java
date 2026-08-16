package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

import java.time.YearMonth;
import java.time.ZonedDateTime;

/**
 * Exception thrown when attempting to import historical data to a month before the startPeriod.
 * Historical imports are only allowed to months between startPeriod and activePeriod-1.
 */
public class ImportDateBeforeStartPeriodException extends BusinessException {

    private final ZonedDateTime importDate;
    private final YearMonth targetPeriod;
    private final YearMonth startPeriod;

    public ImportDateBeforeStartPeriodException(ZonedDateTime importDate, YearMonth targetPeriod, YearMonth startPeriod) {
        super(String.format("Cannot import historical transaction with date [%s] (period [%s]). " +
                        "Historical imports are only allowed to months starting from [%s].",
                importDate, targetPeriod, startPeriod));
        this.importDate = importDate;
        this.targetPeriod = targetPeriod;
        this.startPeriod = startPeriod;
    }

    public ZonedDateTime getImportDate() {
        return importDate;
    }

    public YearMonth getTargetPeriod() {
        return targetPeriod;
    }

    public YearMonth getStartPeriod() {
        return startPeriod;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.IMPORT_DATE_BEFORE_START;
    }
}
