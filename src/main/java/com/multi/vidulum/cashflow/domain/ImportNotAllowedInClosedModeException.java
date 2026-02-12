package com.multi.vidulum.cashflow.domain;

/**
 * Exception thrown when attempting to import a transaction to a CLOSED CashFlow.
 * <p>
 * Once a CashFlow is closed, no more transactions can be imported.
 */
public class ImportNotAllowedInClosedModeException extends RuntimeException {

    private final CashFlowId cashFlowId;

    public ImportNotAllowedInClosedModeException(CashFlowId cashFlowId) {
        super(String.format("Cannot import to CLOSED CashFlow [%s]. CashFlow is read-only.", cashFlowId.id()));
        this.cashFlowId = cashFlowId;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }
}
