package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.BankName;
import com.multi.vidulum.common.CashFlowId;

import java.time.ZonedDateTime;

public record OwnedBankAccount(
        OwnedAccountNumber bankAccountNumber,
        BankName bankName,
        String label,
        AccountStatus status,
        AccountSource source,
        CashFlowId linkedCashFlowId,
        ZonedDateTime addedAt,
        ZonedDateTime closedAt
) {
    public String rawIban() {
        return bankAccountNumber.iban();
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean matches(String iban) {
        return rawIban().equals(iban);
    }
}
