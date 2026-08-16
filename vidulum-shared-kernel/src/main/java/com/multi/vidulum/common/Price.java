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
public class Price {
    private BigDecimal amount;
    private String currency;

    public static Price of(double amount, String currency) {
        return new Price(BigDecimal.valueOf(amount), currency);
    }

    public static Price of(Money money) {
        return new Price(money.getAmount(), money.getCurrency());
    }

    public static Price of(BigDecimal amount, String currency) {
        return new Price(amount, currency);
    }

    public static Price zero(String currency) {
        return Price.of(BigDecimal.ZERO, currency);
    }

    public static Price one(String currency) {
        return Price.of(BigDecimal.ONE, currency);
    }

    public Price multiply(double quantity) {
        BigDecimal newAmount = amount.multiply(BigDecimal.valueOf(quantity));
        return new Price(newAmount, currency);
    }

    public Money multiply(Quantity quantity) {
        BigDecimal newAmount = amount.multiply(BigDecimal.valueOf(quantity.getQty()));
        return new Money(newAmount, currency);
    }

    public Price minus(Price other) {
        return new Price(amount.subtract(other.amount), currency);
    }

    public double diffPct(Price other) {
        return amount.divide(other.amount, 8, RoundingMode.FLOOR).subtract(BigDecimal.ONE).doubleValue();
    }

    public Price plus(Price other) {
        return new Price(amount.add(other.amount), currency);
    }

    public Price divide(double number) {
        return Price.of(amount.divide(BigDecimal.valueOf(number), RoundingMode.CEILING), currency);
    }

    public Price divide(Price other) {
        return Price.of(amount.divide(other.getAmount(), RoundingMode.CEILING), currency);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Price money = (Price) o;
        return amount.compareTo(money.amount) == 0 && Objects.equals(currency, money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.doubleValue(), currency);
    }

    public Price withScale(int scale) {
        return new Price(new BigDecimal(amount.toString()).setScale(scale, RoundingMode.HALF_UP), currency);
    }

    public boolean isPositive() {
        return isMoreThan(Price.zero(currency));
    }

    public boolean isMoreThan(Price other) {
        return this.amount.compareTo(other.amount) > 0;
    }
}
