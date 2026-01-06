package com.multi.vidulum.cashflow.domain;

/**
 * Exception thrown when attempting to activate a CashFlow that is not in SETUP mode.
 * Activation can only be performed on CashFlows in SETUP mode.
 */
public class ActivationNotAllowedInNonSetupModeException extends RuntimeException {

    private final CashFlowId cashFlowId;
    private final CashFlow.CashFlowStatus currentStatus;

    public ActivationNotAllowedInNonSetupModeException(CashFlowId cashFlowId, CashFlow.CashFlowStatus currentStatus) {
        super(String.format("Cannot activate CashFlow [%s]. Activation is only allowed in SETUP mode. " +
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
