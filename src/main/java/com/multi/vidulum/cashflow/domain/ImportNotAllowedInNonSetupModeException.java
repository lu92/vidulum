package com.multi.vidulum.cashflow.domain;

/**
 * Exception thrown when attempting to import historical data to a CashFlow that is not in SETUP mode.
 */
public class ImportNotAllowedInNonSetupModeException extends RuntimeException {

    private final CashFlowId cashFlowId;
    private final CashFlow.CashFlowStatus currentStatus;

    public ImportNotAllowedInNonSetupModeException(CashFlowId cashFlowId, CashFlow.CashFlowStatus currentStatus) {
        super(String.format("Historical import is only allowed in SETUP mode. CashFlow [%s] is in [%s] mode.",
                cashFlowId.id(), currentStatus));
        this.cashFlowId = cashFlowId;
        this.currentStatus = currentStatus;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }

    public CashFlow.CashFlowStatus getCurrentStatus() {
        return currentStatus;
    }
}
