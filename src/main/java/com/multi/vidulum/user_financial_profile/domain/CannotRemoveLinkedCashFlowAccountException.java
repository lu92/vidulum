package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.UserId;

public class CannotRemoveLinkedCashFlowAccountException extends RuntimeException {
    public CannotRemoveLinkedCashFlowAccountException(UserId userId, String iban, CashFlowId cashFlowId) {
        super("Cannot remove bank account [" + iban + "] from profile of user [" + userId.getId()
                + "] because it is linked to active CashFlow [" + cashFlowId.id() + "]");
    }
}
