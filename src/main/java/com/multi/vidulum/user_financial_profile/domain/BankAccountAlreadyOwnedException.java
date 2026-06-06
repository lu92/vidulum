package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.UserId;

public class BankAccountAlreadyOwnedException extends RuntimeException {
    public BankAccountAlreadyOwnedException(UserId userId, String iban) {
        super("User [" + userId.getId() + "] already owns bank account [" + iban + "]");
    }
}
