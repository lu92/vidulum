package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class UserFinancialProfileNotFoundException extends BusinessException {
    public UserFinancialProfileNotFoundException(UserId userId) {
        super("User financial profile not found for user [" + userId.getId() + "]");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.OWNED_ACCOUNT_PROFILE_NOT_FOUND;
    }
}
