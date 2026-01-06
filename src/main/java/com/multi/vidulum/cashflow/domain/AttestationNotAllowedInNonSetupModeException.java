package com.multi.vidulum.cashflow.domain;

/**
 * Exception thrown when attempting to attest historical import for a CashFlow that is not in SETUP mode.
 * Attestation can only be performed on CashFlows in SETUP mode.
 */
public class AttestationNotAllowedInNonSetupModeException extends RuntimeException {

    private final CashFlowId cashFlowId;
    private final CashFlow.CashFlowStatus currentStatus;

    public AttestationNotAllowedInNonSetupModeException(CashFlowId cashFlowId, CashFlow.CashFlowStatus currentStatus) {
        super(String.format("Cannot attest historical import for CashFlow [%s]. Attestation is only allowed in SETUP mode. " +
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
