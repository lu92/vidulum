package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Side;

public class NotSufficientBalance extends RuntimeException {
    public NotSufficientBalance(Money money) {
        super(String.format("Not sufficient amount of money [%s]", money));
    }

    public NotSufficientBalance(Side side, Money money) {
        super(String.format("Attempt to [%s] [%s] - not sufficient amount!", side, money));
    }
}
