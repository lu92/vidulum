package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.UserId;

public class UserFinancialProfileNotFoundException extends RuntimeException {
    public UserFinancialProfileNotFoundException(UserId userId) {
        super("User financial profile not found for user [" + userId.getId() + "]");
    }
}
