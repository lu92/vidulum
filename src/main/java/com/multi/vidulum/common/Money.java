package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Money {
    private BigDecimal amount;
    private String currency;

    public static Money of(double amount, String currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money zero(String currency) {
        return Money.of(BigDecimal.ZERO, currency);
    }

    public static Money one(String currency) {
        return Money.of(BigDecimal.ONE, currency);
    }

    public Money multiply(double quantity) {
        BigDecimal newAmount = amount.multiply(BigDecimal.valueOf(quantity));
        return new Money(newAmount, currency);
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount), currency);
    }

    public double diffPct(Money other) {
        return amount.divide(other.amount, RoundingMode.CEILING).subtract(BigDecimal.ONE).doubleValue();
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount), currency);
    }

    public Money divide(double number) {
        return Money.of(amount.divide(BigDecimal.valueOf(number), RoundingMode.CEILING), currency);
    }

    public Money divide(Money other) {
        return Money.of(amount.divide(other.getAmount(), RoundingMode.CEILING), currency);
    }
}
