package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.Money;

public record BankAccount(BankName bankName, AccountNumber accountNumber, Money balance) {

    public BankAccount withUpdatedBalance(Money updatedBalance) {
        return new BankAccount(bankName, accountNumber, updatedBalance);
    }

}
