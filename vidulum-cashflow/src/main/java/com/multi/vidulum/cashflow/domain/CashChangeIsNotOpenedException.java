package com.multi.vidulum.cashflow.domain;
import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class CashChangeIsNotOpenedException extends BusinessException {

    private CashChangeId id;
    private Type type;
    public CashChangeIsNotOpenedException(Type type, CashChangeId id) {
        super(String.format("Cash change [%s] [%s] is not opened", type, id));
        this.id = id;
        this.type = type;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CASHCHANGE_NOT_PENDING;
    }
}
