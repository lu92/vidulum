package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.BankName;
import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.time.ZonedDateTime;

public record OwnedBankAccount(
        BankAccountNumber bankAccountNumber,
        BankName bankName,
        String label,
        AccountStatus status,
        AccountSource source,
        CashFlowId linkedCashFlowId,
        ZonedDateTime addedAt,
        ZonedDateTime closedAt
) {
    public String rawIban() {
        return bankAccountNumber.fetchRawIban();
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean matches(String iban) {
        return rawIban().equals(iban);
    }
}
