package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

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

    public Money multiply(Quantity quantity) {
        BigDecimal newAmount = amount.multiply(BigDecimal.valueOf(quantity.getQty()));
        return new Money(newAmount, currency);
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount), currency);
    }

    public double diffPct(Money other) {
        return amount.divide(other.amount, 8, RoundingMode.FLOOR).subtract(BigDecimal.ONE).doubleValue();
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return amount.compareTo(money.amount) == 0 && Objects.equals(currency, money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.doubleValue(), currency);
    }

    public Money withScale(int scale) {
        return new Money(new BigDecimal(amount.toString()).setScale(scale, RoundingMode.HALF_UP), currency);
    }

    public boolean isPositive() {
        return isMoreThan(Money.zero(currency));
    }

    public boolean isMoreThan(Money other) {
        return this.amount.compareTo(other.amount) > 0;
    }
}
