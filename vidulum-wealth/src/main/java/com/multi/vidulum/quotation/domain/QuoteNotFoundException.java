package com.multi.vidulum.quotation.domain;


import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * No price is cached for the symbol yet. Common and expected — quotes arrive asynchronously —
 * so it must read as a 404 and not as an internal error.
 */
public class QuoteNotFoundException extends BusinessException {

    public QuoteNotFoundException(Symbol symbol) {
        super(String.format("Ticker [%s] not found", symbol.getId()));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.QUOTE_NOT_FOUND;
    }
}
