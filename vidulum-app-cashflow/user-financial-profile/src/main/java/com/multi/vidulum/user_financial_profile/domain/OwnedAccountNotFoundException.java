package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class OwnedAccountNotFoundException extends BusinessException {
    public OwnedAccountNotFoundException(UserId userId, String iban) {
        super("Bank account [" + iban + "] not found in profile of user [" + userId.getId() + "]");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.OWNED_ACCOUNT_NOT_FOUND;
    }
}
