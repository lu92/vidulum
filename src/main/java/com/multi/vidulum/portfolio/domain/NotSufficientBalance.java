package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.Money;

public class NotSufficientBalance extends RuntimeException {
    public NotSufficientBalance(Money money) {
        super(String.format("Not sufficient amount of money [%s]", money));
    }
}
