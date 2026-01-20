package com.multi.vidulum.cashflow.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.multi.vidulum.common.Currency;

public record BankAccountNumber(
        @JsonProperty("accountNumber") String account,
        @JsonProperty("currency") Currency denomination) {
}
