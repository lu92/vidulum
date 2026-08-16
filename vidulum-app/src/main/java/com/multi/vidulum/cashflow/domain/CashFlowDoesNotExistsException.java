package com.multi.vidulum.cashflow.domain;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class CashFlowDoesNotExistsException extends BusinessException {

    private CashFlowId id;

    public CashFlowDoesNotExistsException(CashFlowId id) {
        super(String.format("Cash flow [%s] does not exists", id));
        this.id = id;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CASHFLOW_NOT_FOUND;
    }
}
