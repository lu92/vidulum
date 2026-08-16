package com.multi.vidulum.quotation.domain;


import com.multi.vidulum.common.Symbol;

public class QuoteNotFoundException extends RuntimeException {

    public QuoteNotFoundException(Symbol symbol) {
        super(String.format("Ticker [%s] not found", symbol.getId()));
    }
}

