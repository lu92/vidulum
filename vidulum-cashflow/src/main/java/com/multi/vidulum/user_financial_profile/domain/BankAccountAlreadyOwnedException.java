package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class BankAccountAlreadyOwnedException extends BusinessException {
    public BankAccountAlreadyOwnedException(UserId userId, String iban) {
        super("User [" + userId.getId() + "] already owns bank account [" + iban + "]");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.OWNED_ACCOUNT_ALREADY_EXISTS;
    }
}
