package com.multi.vidulum.exchange_connection.api.error;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.exchange_connection.api.ExchangeConnectionId;

public class ExchangeConnectionNotFoundException extends BusinessException {

    public ExchangeConnectionNotFoundException(ExchangeConnectionId id) {
        super("Exchange connection [" + id.getId() + "] not found");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.EXCHANGE_CONNECTION_NOT_FOUND;
    }
}
