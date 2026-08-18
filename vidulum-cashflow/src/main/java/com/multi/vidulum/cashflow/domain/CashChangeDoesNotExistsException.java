package com.multi.vidulum.cashflow.domain;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class CashChangeDoesNotExistsException extends BusinessException {

    private CashChangeId id;

    public CashChangeDoesNotExistsException(CashChangeId id) {
        super(String.format("Cash change [%s] does not exists", id.id()));
        this.id = id;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CASHCHANGE_NOT_FOUND;
    }
}
