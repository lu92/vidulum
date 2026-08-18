package com.multi.vidulum.cashflow.domain;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Exception thrown when attempting to import historical data to a CashFlow that is not in SETUP mode.
 */
public class ImportNotAllowedInNonSetupModeException extends BusinessException {

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

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CASHFLOW_IMPORT_NOT_ALLOWED;
    }
}
