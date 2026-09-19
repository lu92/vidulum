package com.multi.vidulum.okx.exchange_connection.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class ExchangeConnectionNotFoundException extends BusinessException {

    public ExchangeConnectionNotFoundException(ExchangeConnectionId id) {
        super("Exchange connection [" + id.getId() + "] not found");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.EXCHANGE_CONNECTION_NOT_FOUND;
    }
}
