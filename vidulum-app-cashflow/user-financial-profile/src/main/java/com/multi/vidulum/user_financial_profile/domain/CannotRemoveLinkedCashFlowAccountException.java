package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class CannotRemoveLinkedCashFlowAccountException extends BusinessException {
    public CannotRemoveLinkedCashFlowAccountException(UserId userId, String iban, CashFlowId cashFlowId) {
        super("Cannot remove bank account [" + iban + "] from profile of user [" + userId.getId()
                + "] because it is linked to active CashFlow [" + cashFlowId.id() + "]");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.OWNED_ACCOUNT_CASHFLOW_LINKED;
    }
}
