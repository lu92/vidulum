package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.UserId;

public class OwnedAccountNotFoundException extends RuntimeException {
    public OwnedAccountNotFoundException(UserId userId, String iban) {
        super("Bank account [" + iban + "] not found in profile of user [" + userId.getId() + "]");
    }
}
