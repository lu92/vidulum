package com.multi.vidulum.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class ExchangeRate {
    private String symbol;
    private BigDecimal rate;

    public ExchangeRate(String symbol, double rate) {
        this.symbol = symbol;
        this.rate = BigDecimal.valueOf(rate);
    }

    public static ExchangeRate equivalent(String ticker) {
        return new ExchangeRate(String.format("%s/%s)", ticker, ticker), 1.0);
    }
}
