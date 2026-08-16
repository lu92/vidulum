package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class OperationNotAllowedInSetupModeException extends BusinessException {

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

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CASHFLOW_OPERATION_NOT_ALLOWED_IN_SETUP;
    }
}
