package com.multi.vidulum.cashflow.domain;

import java.time.YearMonth;

/**
 * Exception thrown when attempting to import a transaction to a FORECASTED month.
 * <p>
 * FORECASTED months represent future periods and cannot receive imported transactions.
 * Only ACTIVE, ROLLED_OVER, and IMPORTED months allow bank data import.
 */
public class ImportToForecastedMonthNotAllowedException extends RuntimeException {

    private final CashFlowId cashFlowId;
    private final YearMonth targetPeriod;
    private final YearMonth activePeriod;

    public ImportToForecastedMonthNotAllowedException(CashFlowId cashFlowId, YearMonth targetPeriod, YearMonth activePeriod) {
        super(String.format(
                "Cannot import to FORECASTED month %s in CashFlow [%s]. Current active period is %s. " +
                        "Only import to current or past months is allowed.",
                targetPeriod, cashFlowId.id(), activePeriod));
        this.cashFlowId = cashFlowId;
        this.targetPeriod = targetPeriod;
        this.activePeriod = activePeriod;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }

    public YearMonth getTargetPeriod() {
        return targetPeriod;
    }

    public YearMonth getActivePeriod() {
        return activePeriod;
    }
}
