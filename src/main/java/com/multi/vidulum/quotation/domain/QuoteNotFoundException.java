package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.Ticker;

public class QuoteNotFoundException extends RuntimeException{

    public QuoteNotFoundException(Ticker ticker) {
        super(String.format("Ticker [%s] not found", ticker.getId()));
    }
}
