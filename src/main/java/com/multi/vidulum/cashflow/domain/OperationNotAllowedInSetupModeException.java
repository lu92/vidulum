package com.multi.vidulum.cashflow.domain;

public class OperationNotAllowedInSetupModeException extends RuntimeException {

    private final String operationName;
    private final CashFlowId cashFlowId;

    public OperationNotAllowedInSetupModeException(String operationName, CashFlowId cashFlowId) {
        super(String.format("Operation [%s] is not allowed in SETUP mode for CashFlow [%s]. " +
                "Complete setup and activate CashFlow first.", operationName, cashFlowId.id()));
        this.operationName = operationName;
        this.cashFlowId = cashFlowId;
    }

    public String getOperationName() {
        return operationName;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }
}
