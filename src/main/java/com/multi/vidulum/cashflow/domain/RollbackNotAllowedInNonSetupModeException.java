package com.multi.vidulum.cashflow.domain;

/**
 * Exception thrown when attempting to rollback import for a CashFlow that is not in SETUP mode.
 * Rollback can only be performed on CashFlows in SETUP mode.
 */
public class RollbackNotAllowedInNonSetupModeException extends RuntimeException {

    private final CashFlowId cashFlowId;
    private final CashFlow.CashFlowStatus currentStatus;

    public RollbackNotAllowedInNonSetupModeException(CashFlowId cashFlowId, CashFlow.CashFlowStatus currentStatus) {
        super(String.format("Cannot rollback import for CashFlow [%s]. Rollback is only allowed in SETUP mode. " +
                "Current status: [%s].", cashFlowId.id(), currentStatus));
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
