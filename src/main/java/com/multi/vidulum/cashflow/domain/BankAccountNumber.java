package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.Currency;

public record BankAccountNumber(String account, Currency denomination) {
}
